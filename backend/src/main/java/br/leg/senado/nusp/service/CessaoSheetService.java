package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.repository.SalaRepository;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.Color;
import com.google.api.services.sheets.v4.model.GridData;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lê cessões de sala da planilha Google Sheets ("Reserva Plenários das comissões").
 *
 * Identificação de cessão: célula com cor de fundo {@code #FFF2CC} (creme).
 * As demais cores representam comissões regulares (azul/marrom) e não são lidas.
 *
 * Layout da planilha:
 *   - Coluna A   = data (forward-fill: vale para as próximas linhas até nova data)
 *   - Colunas B/D/F/H/J/L/N/P = horário do plenário correspondente
 *   - Colunas C/E/G/I/K/M/O/Q = evento do plenário correspondente
 *   - Coluna R   = Sala de Reuniões
 *
 * Polling a cada {@code app.sheets.refresh-interval-sec} segundos. Cache em memória.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CessaoSheetService {

    private final SalaRepository salaRepository;

    @Value("${app.sheets.credentials-path}")
    private String credentialsPath;

    @Value("${app.sheets.spreadsheet-id:}")
    private String spreadsheetId;

    @Value("${app.sheets.cessao-colors-hex:FFF2CC,FCE5CD}")
    private String cessaoColorsHex;

    private static final String APPLICATION_NAME = "NUSP-SenadoApp";
    private static final double COLOR_TOLERANCE = 0.02;  // tolerância p/ comparar floats RGB

    // ── Cache ────────────────────────────────────────────────────
    private volatile List<Map<String, Object>> cacheCessoes = Collections.emptyList();
    private volatile String lastHash = "";
    private volatile boolean enabled = false;

    // ── Cliente Sheets (lazy) ─────────────────────────────────────
    private volatile Sheets sheetsClient;

    // ── Cores-alvo (cada item = [r, g, b] em float 0..1) ─────────
    private List<float[]> targetColors = Collections.emptyList();

    // ── Mapeamento coluna planilha → nome de sala ────────────────
    private static final Map<String, String> COL_TO_SALA = Map.of(
            "C", "Plenário 06",
            "E", "Plenário 02",
            "G", "Plenário 03",
            "I", "Plenário 07",
            "K", "Plenário 09",
            "M", "Plenário 13",
            "O", "Plenário 15",
            "Q", "Plenário 19",
            "R", "Sala de Reuniões"
    );

    // ── Mapeamento nome de sala → sala_id (carregado após DB up) ─
    private volatile Map<String, Integer> salaNomeToId = null;

    // ══ Inicialização ═══════════════════════════════════════════

    @PostConstruct
    public void init() {
        if (spreadsheetId == null || spreadsheetId.isBlank()) {
            log.warn("CessaoSheetService desabilitado: app.sheets.spreadsheet-id não configurado");
            return;
        }
        if (!Files.exists(Path.of(credentialsPath))) {
            log.warn("CessaoSheetService desabilitado: credencial não encontrada em {}", credentialsPath);
            return;
        }

        // Parse das cores-alvo (separadas por vírgula). Cada hex vira RGB float 0..1
        List<float[]> cores = new ArrayList<>();
        List<String> hexes = new ArrayList<>();
        for (String raw : cessaoColorsHex.split(",")) {
            String hex = raw.trim().replace("#", "").toUpperCase();
            if (hex.isEmpty()) continue;
            if (hex.length() == 8) hex = hex.substring(2);  // remove alpha se presente
            float r = Integer.parseInt(hex.substring(0, 2), 16) / 255f;
            float g = Integer.parseInt(hex.substring(2, 4), 16) / 255f;
            float b = Integer.parseInt(hex.substring(4, 6), 16) / 255f;
            cores.add(new float[]{r, g, b});
            hexes.add(hex);
        }
        if (cores.isEmpty()) {
            log.warn("CessaoSheetService desabilitado: nenhuma cor de cessão configurada");
            return;
        }
        this.targetColors = cores;

        try {
            GoogleCredentials credentials;
            try (FileInputStream in = new FileInputStream(credentialsPath)) {
                credentials = GoogleCredentials.fromStream(in)
                        .createScoped(List.of(SheetsScopes.SPREADSHEETS_READONLY));
            }
            this.sheetsClient = new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName(APPLICATION_NAME)
                    .build();
            this.enabled = true;
            log.info("CessaoSheetService inicializado — planilha {}, cores-alvo {}", spreadsheetId, hexes);
        } catch (Exception e) {
            log.error("Falha ao inicializar CessaoSheetService", e);
        }
    }

    // ══ Polling ═════════════════════════════════════════════════

    @Scheduled(
            fixedRateString = "${app.sheets.refresh-interval-sec:60}000",
            initialDelayString = "10000")
    public void poll() {
        if (!enabled) return;
        try {
            carregarMapeamentoSeNecessario();
            List<Map<String, Object>> novas = fetchCessoes(LocalDate.now());
            String hash = String.valueOf(novas.hashCode());
            if (!hash.equals(lastHash)) {
                cacheCessoes = novas;
                lastHash = hash;
                log.info("Cessões atualizadas: {} entradas hoje", novas.size());
            }
        } catch (Exception e) {
            log.error("Erro ao atualizar cessões da planilha", e);
        }
    }

    // ══ API pública ═════════════════════════════════════════════

    /** Cessões de hoje filtradas por sala_id */
    public List<Map<String, Object>> getCessoesPorSala(int salaId) {
        return cacheCessoes.stream()
                .filter(c -> Integer.valueOf(salaId).equals(c.get("sala_id")))
                .collect(Collectors.toList());
    }

    /** Todas as cessões de hoje */
    public List<Map<String, Object>> getCessoes() {
        return cacheCessoes;
    }

    /** Indica se o serviço está habilitado (credencial OK + planilha configurada) */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Busca cessões para uma data arbitrária (sob demanda, sem cache).
     * Útil quando o usuário consulta um dia diferente de hoje.
     */
    public List<Map<String, Object>> fetchCessoesParaData(LocalDate data) {
        if (!enabled) return Collections.emptyList();
        try {
            carregarMapeamentoSeNecessario();
            return fetchCessoes(data);
        } catch (Exception e) {
            log.error("Erro ao buscar cessões para data {}", data, e);
            return Collections.emptyList();
        }
    }

    // ══ Fetch — Sheets API ══════════════════════════════════════

    private List<Map<String, Object>> fetchCessoes(LocalDate hoje) throws Exception {
        String abaNome = nomeAbaParaMes(hoje);

        // Pede só os campos que precisamos: valor formatado + cor de fundo efetiva
        Spreadsheet resp = sheetsClient.spreadsheets().get(spreadsheetId)
                .setRanges(List.of(abaNome))
                .setFields("sheets(properties.title,data(rowData(values(formattedValue,effectiveFormat.backgroundColor))))")
                .execute();

        List<Sheet> sheets = resp.getSheets();
        if (sheets == null || sheets.isEmpty()) {
            log.warn("Aba '{}' não encontrada na planilha", abaNome);
            return Collections.emptyList();
        }
        Sheet sheet = sheets.get(0);
        List<GridData> dataList = sheet.getData();
        if (dataList == null || dataList.isEmpty()) {
            return Collections.emptyList();
        }

        List<RowData> rows = dataList.get(0).getRowData();
        if (rows == null) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate dataAtual = null;

        for (RowData row : rows) {
            if (row == null || row.getValues() == null) continue;
            List<CellData> cells = row.getValues();

            // Coluna A → forward-fill da data
            LocalDate dataDaLinha = parseDataCelula(cells.size() > 0 ? cells.get(0) : null);
            if (dataDaLinha != null) {
                dataAtual = dataDaLinha;
            }
            if (dataAtual == null) continue;
            if (!dataAtual.equals(hoje)) continue;  // só nos importa o dia de hoje

            // Para cada coluna mapeada (C, E, G, ...), checa cor + valor
            for (Map.Entry<String, String> entry : COL_TO_SALA.entrySet()) {
                int colIdx = colLetraParaIndice(entry.getKey());
                if (colIdx >= cells.size()) continue;
                CellData cell = cells.get(colIdx);
                if (cell == null) continue;
                if (!isCessao(cell)) continue;
                String evento = cell.getFormattedValue();
                if (evento == null || evento.isBlank()) continue;

                String horario = null;
                int colHorario = colIdx - 1;  // coluna anterior tem o horário
                if (colHorario >= 0 && colHorario < cells.size() && cells.get(colHorario) != null) {
                    horario = cells.get(colHorario).getFormattedValue();
                }

                String salaNome = entry.getValue();
                Integer salaId = salaNomeToId != null ? salaNomeToId.get(salaNome) : null;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tipo", "cessao");
                item.put("data", dataAtual.toString());
                item.put("sala_nome", salaNome);
                item.put("sala_id", salaId);
                item.put("horario", horario != null ? horario.trim() : "");
                item.put("titulo", evento.trim());
                item.put("local", salaNome);
                result.add(item);
            }
        }

        return result;
    }

    // ══ Mapeamento sala_nome → sala_id ══════════════════════════

    private void carregarMapeamentoSeNecessario() {
        if (salaNomeToId != null) return;
        Map<String, Integer> mapa = new HashMap<>();
        for (Sala sala : salaRepository.findAtivasOrdenadas()) {
            mapa.put(sala.getNome(), sala.getId());
        }
        salaNomeToId = mapa;
        log.info("Mapeamento sala_nome → sala_id carregado: {}", mapa);
    }

    // ══ Helpers ═════════════════════════════════════════════════

    /** Nome da aba para um mês — formato "Mês YY" (ex: "Abril 26"). */
    private String nomeAbaParaMes(LocalDate data) {
        String mes = data.getMonth().getDisplayName(TextStyle.FULL, new Locale("pt", "BR"));
        // Capitalizar primeira letra (Locale pode retornar minúsculo em alguns sistemas)
        mes = Character.toUpperCase(mes.charAt(0)) + mes.substring(1);
        String ano2 = String.format("%02d", data.getYear() % 100);
        return mes + " " + ano2;
    }

    private static final Pattern DATA_PT = Pattern.compile(
            "(\\d{1,2})\\s+de\\s+(\\p{L}+)\\s+de\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Tenta extrair uma data de uma célula da coluna A (pode vir como Date, "DD/MM/YYYY", "DD/MM" ou texto). */
    private LocalDate parseDataCelula(CellData cell) {
        if (cell == null || cell.getFormattedValue() == null) return null;
        String txt = cell.getFormattedValue().trim();
        if (txt.isEmpty()) return null;

        // Formato "DD/MM/YYYY"
        Matcher m1 = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})").matcher(txt);
        if (m1.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m1.group(3)),
                        Integer.parseInt(m1.group(2)), Integer.parseInt(m1.group(1)));
            } catch (Exception ignore) {}
        }

        // Formato "DD/MM" — usa o ano corrente
        Matcher m2 = Pattern.compile("^(\\d{1,2})/(\\d{1,2})$").matcher(txt);
        if (m2.find()) {
            try {
                return LocalDate.of(LocalDate.now().getYear(),
                        Integer.parseInt(m2.group(2)), Integer.parseInt(m2.group(1)));
            } catch (Exception ignore) {}
        }

        // Formato "quarta-feira, 1 de abril de 2026"
        Matcher m3 = DATA_PT.matcher(txt);
        if (m3.find()) {
            int dia = Integer.parseInt(m3.group(1));
            int mes = mesPtParaNumero(m3.group(2));
            int ano = Integer.parseInt(m3.group(3));
            if (mes > 0) {
                try {
                    return LocalDate.of(ano, mes, dia);
                } catch (Exception ignore) {}
            }
        }
        return null;
    }

    private int mesPtParaNumero(String mes) {
        return switch (mes.toLowerCase().trim()) {
            case "janeiro" -> 1;
            case "fevereiro" -> 2;
            case "março", "marco" -> 3;
            case "abril" -> 4;
            case "maio" -> 5;
            case "junho" -> 6;
            case "julho" -> 7;
            case "agosto" -> 8;
            case "setembro" -> 9;
            case "outubro" -> 10;
            case "novembro" -> 11;
            case "dezembro" -> 12;
            default -> 0;
        };
    }

    /** Converte letra de coluna ("A"=0, "B"=1, ..., "Z"=25, "AA"=26) para índice 0-based. */
    private int colLetraParaIndice(String letra) {
        int idx = 0;
        for (char c : letra.toUpperCase().toCharArray()) {
            idx = idx * 26 + (c - 'A' + 1);
        }
        return idx - 1;
    }

    /** Verifica se a cor de fundo da célula bate com alguma das cores-alvo. */
    private boolean isCessao(CellData cell) {
        if (cell.getEffectiveFormat() == null) return false;
        Color bg = cell.getEffectiveFormat().getBackgroundColor();
        if (bg == null) return false;
        float r = bg.getRed() != null ? bg.getRed() : 0f;
        float g = bg.getGreen() != null ? bg.getGreen() : 0f;
        float b = bg.getBlue() != null ? bg.getBlue() : 0f;
        for (float[] alvo : targetColors) {
            if (Math.abs(r - alvo[0]) < COLOR_TOLERANCE
                    && Math.abs(g - alvo[1]) < COLOR_TOLERANCE
                    && Math.abs(b - alvo[2]) < COLOR_TOLERANCE) {
                return true;
            }
        }
        return false;
    }
}
