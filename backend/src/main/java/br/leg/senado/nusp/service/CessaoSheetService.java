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
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lê cessões de sala da planilha Google Sheets ("Reserva Plenários das comissões").
 *
 * Identificação de cessão: célula com cor de fundo creme ({@code #FFF2CC} ou {@code #FCE5CD})
 * e/ou, no layout novo, o marcador textual "Cessão". As demais cores representam comissões
 * regulares (azul/marrom) e não são lidas.
 *
 * A planilha existe em dois layouts (detectados dinamicamente — ver {@link #detectarLayout}):
 *
 *   - ANTIGO (até maio/2026): cada plenário ocupa 2 colunas — {@code [horário][evento]}.
 *       Cabeçalhos "Plenário N" espaçados de 2 em 2 (B, D, F, H, J, L, N, P).
 *   - NOVO (a partir de junho/2026): cada plenário ocupa 4 colunas —
 *       {@code [horário]["Cessão"][descrição][status]}. Cabeçalhos espaçados de 4 em 4
 *       (B, F, J, N, R, V, Z, AD).
 *
 * Em ambos: coluna A = data (forward-fill, vale para as próximas linhas até nova data) e
 * "Sala de Reuniões" à direita do último plenário (cabeçalho na 1ª linha).
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

    // ── Cabeçalho de plenário na planilha (ex: "Plenário 6", "Plenário 13") ──
    private static final Pattern PLENARIO_HEADER = Pattern.compile(
            "plen[aá]rio\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

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

        // Detecta o layout (antigo 2 col / novo 4 col) a partir do cabeçalho da aba
        List<Bloco> blocos = detectarLayout(rows);
        if (blocos.isEmpty()) {
            log.warn("Layout da aba '{}' não reconhecido (nenhum cabeçalho de plenário)", abaNome);
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate dataAtual = null;
        int linhasNoBlocoAtual = 0;
        // Cada dia ocupa exatamente 3 linhas na planilha (3 horários por dia).
        // Após esse limite, descartamos o forward-fill para não capturar
        // dados de seções "template" da planilha (ex: semana modelo no rodapé).
        final int MAX_LINHAS_POR_DIA = 3;

        for (RowData row : rows) {
            if (row == null || row.getValues() == null) {
                if (dataAtual != null && ++linhasNoBlocoAtual >= MAX_LINHAS_POR_DIA) {
                    dataAtual = null;
                }
                continue;
            }
            List<CellData> cells = row.getValues();

            // Coluna A → forward-fill da data (limitado a 3 linhas)
            LocalDate dataDaLinha = parseDataCelula(cells.size() > 0 ? cells.get(0) : null);
            if (dataDaLinha != null) {
                dataAtual = dataDaLinha;
                linhasNoBlocoAtual = 0;
            } else if (dataAtual != null && ++linhasNoBlocoAtual >= MAX_LINHAS_POR_DIA) {
                dataAtual = null;
                continue;
            }
            if (dataAtual == null) continue;
            if (!dataAtual.equals(hoje)) continue;  // só a data alvo

            // Para cada bloco de plenário detectado, checa cessão (cor/marcador) + valor
            for (Bloco b : blocos) {
                if (b.colEvento() >= cells.size()) continue;
                CellData evCell = cells.get(b.colEvento());
                if (evCell == null) continue;

                // Cessão = evento creme OU (layout novo) marcador "Cessão" na coluna do meio
                boolean ehCessao = isCessao(evCell);
                if (!ehCessao && b.colMarcador() >= 0 && b.colMarcador() < cells.size()) {
                    ehCessao = isMarcadorCessao(cells.get(b.colMarcador()));
                }
                if (!ehCessao) continue;

                String evento = evCell.getFormattedValue();
                if (evento == null || evento.isBlank()) continue;

                String horario = null;
                if (b.colHorario() >= 0 && b.colHorario() < cells.size()
                        && cells.get(b.colHorario()) != null) {
                    horario = cells.get(b.colHorario()).getFormattedValue();
                }

                String salaNome = b.salaNome();
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

    /** "DD/MM/YYYY" em qualquer posição (com ou sem prefixo, ex: "01/06/2026"). */
    private static final Pattern DATA_DMY = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");
    /** "DD/MM" sem ano, possivelmente com prefixo de dia da semana (ex: "seg, 1/6"). O
     *  lookbehind/lookahead evitam casar o trecho "DD/MM" de uma data "DD/MM/AAAA". */
    private static final Pattern DATA_DM = Pattern.compile("(?<!\\d/)(\\d{1,2})/(\\d{1,2})(?!/\\d)");

    /** Tenta extrair uma data de uma célula da coluna A. Formatos aceitos:
     *  "DD/MM/YYYY", "DD/MM", "seg, 1/6" (dia da semana + DD/MM) e "quarta-feira, 1 de abril de 2026". */
    private LocalDate parseDataCelula(CellData cell) {
        if (cell == null || cell.getFormattedValue() == null) return null;
        String txt = cell.getFormattedValue().trim();
        if (txt.isEmpty()) return null;

        // Formato "DD/MM/YYYY" (com ou sem prefixo)
        Matcher m1 = DATA_DMY.matcher(txt);
        if (m1.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m1.group(3)),
                        Integer.parseInt(m1.group(2)), Integer.parseInt(m1.group(1)));
            } catch (Exception ignore) {}
        }

        // Formato "DD/MM" sem ano (ex: "seg, 1/6") — usa o ano corrente
        Matcher m2 = DATA_DM.matcher(txt);
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

    // ══ Detecção de layout (cabeçalho dinâmico) ═════════════════

    /** Bloco de colunas de um plenário/sala dentro de uma linha (colMarcador = -1 no layout antigo). */
    private record Bloco(int colHorario, int colEvento, int colMarcador, String salaNome) {}

    /**
     * Lê o cabeçalho da aba e monta os blocos de cada plenário, detectando se o layout é o
     * antigo (2 colunas/plenário) ou o novo (4 colunas/plenário) pela distância entre os
     * cabeçalhos "Plenário N". Retorna lista vazia se nenhum cabeçalho for encontrado.
     */
    private List<Bloco> detectarLayout(List<RowData> rows) {
        // 1. Linha de cabeçalho = a que tem mais células "Plenário N" entre as primeiras 8
        int headerRow = -1, melhor = 0;
        int limite = Math.min(8, rows.size());
        for (int i = 0; i < limite; i++) {
            int cnt = contarPlenarios(rows.get(i));
            if (cnt > melhor) { melhor = cnt; headerRow = i; }
        }
        if (headerRow < 0) return Collections.emptyList();

        // 2. Coluna → nome de sala (zero-padded p/ casar com CAD_SALA: "Plenário 06")
        TreeMap<Integer, String> plenarios = new TreeMap<>();
        List<CellData> hcells = rows.get(headerRow).getValues();
        for (int ci = 0; ci < hcells.size(); ci++) {
            Matcher m = PLENARIO_HEADER.matcher(valor(hcells.get(ci)));
            if (m.find()) {
                plenarios.put(ci, String.format("Plenário %02d", Integer.parseInt(m.group(1))));
            }
        }
        if (plenarios.isEmpty()) return Collections.emptyList();

        // 3. Largura do bloco = menor distância entre cabeçalhos consecutivos (2=antigo, 4=novo)
        List<Integer> cols = new ArrayList<>(plenarios.keySet());
        int largura = Integer.MAX_VALUE;
        for (int i = 1; i < cols.size(); i++) {
            largura = Math.min(largura, cols.get(i) - cols.get(i - 1));
        }
        if (largura == Integer.MAX_VALUE || largura < 2) largura = 2;  // 1 só plenário → assume antigo

        // 4. Offsets dentro do bloco
        int eventoOffset = largura >= 4 ? 2 : 1;     // descrição
        int marcadorOffset = largura >= 4 ? 1 : -1;  // "Cessão" (só no layout novo)

        List<Bloco> blocos = new ArrayList<>();
        for (Map.Entry<Integer, String> e : plenarios.entrySet()) {
            int s = e.getKey();
            int colMarc = marcadorOffset >= 0 ? s + marcadorOffset : -1;
            blocos.add(new Bloco(s, s + eventoOffset, colMarc, e.getValue()));
        }

        // 5. "Sala de Reuniões" (cabeçalho costuma ficar na 1ª linha, à direita dos plenários)
        Integer colReuniao = acharSalaReunioes(rows, headerRow);
        if (colReuniao != null) {
            int s = colReuniao;
            int colMarc = marcadorOffset >= 0 ? s + marcadorOffset : -1;
            blocos.add(new Bloco(s, s + eventoOffset, colMarc, "Sala de Reuniões"));
        }

        log.debug("Layout cessões detectado: headerRow={}, largura={}, blocos={}",
                headerRow, largura, blocos);
        return blocos;
    }

    /** Conta quantas células de uma linha são cabeçalho "Plenário N". */
    private int contarPlenarios(RowData row) {
        if (row == null || row.getValues() == null) return 0;
        int n = 0;
        for (CellData c : row.getValues()) {
            if (PLENARIO_HEADER.matcher(valor(c)).find()) n++;
        }
        return n;
    }

    /** Procura a coluna do cabeçalho "Sala de Reuniões" nas linhas 0..ateLinha (inclusive). */
    private Integer acharSalaReunioes(List<RowData> rows, int ateLinha) {
        for (int i = 0; i <= ateLinha && i < rows.size(); i++) {
            RowData row = rows.get(i);
            if (row == null || row.getValues() == null) continue;
            List<CellData> cells = row.getValues();
            for (int ci = 0; ci < cells.size(); ci++) {
                if (semAcento(valor(cells.get(ci))).startsWith("sala de reuni")) return ci;
            }
        }
        return null;
    }

    /** Valor textual (trim) de uma célula, ou "" se vazia. */
    private String valor(CellData cell) {
        return cell != null && cell.getFormattedValue() != null
                ? cell.getFormattedValue().trim() : "";
    }

    /** True se a célula contém o marcador textual "Cessão" (tolerante a acento/caixa). */
    private boolean isMarcadorCessao(CellData cell) {
        return semAcento(valor(cell)).contains("cessao");
    }

    /** Minúsculas sem diacríticos, para comparação tolerante a acentos. */
    private static String semAcento(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase();
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
