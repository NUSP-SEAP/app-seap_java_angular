package br.leg.senado.nusp.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser do cartão-ponto Secullum (texto extraído de UMA página via OpenPDF).
 *
 * <p>Objetivo único: identificar, em cada linha-dia, em qual das 7 colunas
 * (DIA, ENT. 1, SAÍ. 1, ENT. 2, SAÍ. 2, TOTALDIA, BANCO) cada registro entra, e
 * copiar o texto <b>verbatim</b> — sem traduzir/expandir status (Feriado, Falta,
 * DISPOSI, FERNC, Atesc, …), apenas ocultando os marcadores de batida ({@code * ¨ ^}).
 * Os funcionários já conhecem os textos das folhas; só replicamos.
 *
 * <p>Gramática validada contra 6406 linhas (229 páginas, 7 períodos):
 * <pre>
 *   LINHA  := " " dd/mm/aa " - " DIASEM CORPO
 *   DIASEM := seg|ter|qua|qui|sex|sáb|dom|feri      (feri = feriado; troca o dia da semana)
 *   CORPO  := CÉLULA{0..4} DELTA{0..2}
 *   CÉLULA := BATIDA | STATUS
 *   BATIDA := [*¨^]? hh:mm [*¨^]?                    (SEM sinal → ENT.1/SAÍ.1/ENT.2/SAÍ.2 na ordem)
 *   DELTA  := [+-]hh:mm                              (COM sinal → penúltimo=TOTALDIA, último=BANCO)
 *   STATUS := corrida de letras repetida ×2 (ENT.1/SAÍ.1) ou ×4 (as quatro)
 * </pre>
 * Status e batidas nunca aparecem na mesma linha (verificado).
 */
public final class SecullumFolhaParser {

    private SecullumFolhaParser() {}

    /** Uma linha-dia com o texto VERBATIM de cada uma das 7 colunas (campos vazios = ""). */
    public record LinhaPonto(
            String dia, String ent1, String sai1, String ent2, String sai2,
            String totalDia, String banco) {}

    /** Início de uma linha-dia: dd/mm/aa (ano com 2 dígitos) seguido de " - ". */
    private static final Pattern LINHA_DIA =
            Pattern.compile("^\\s*(\\d{2})/(\\d{2})/(\\d{2})\\s*-\\s*(.*\\S)\\s*$");

    /** Marcadores de dia da semana / feriado (nenhum é prefixo de outro). */
    private static final String[] DIAS_SEMANA = {"seg", "ter", "qua", "qui", "sex", "sáb", "dom", "feri"};

    /** Batida: hh:mm sem sinal, com marcadores opcionais (* ¨ ^) que são descartados. */
    private static final Pattern BATIDA = Pattern.compile("[*¨^]?(\\d{2}:\\d{2})[*¨^]?");

    /** Delta (TOTALDIA/BANCO): hh:mm COM sinal. */
    private static final Pattern DELTA = Pattern.compile("[+-]\\d{2}:\\d{2}");

    private static final Pattern TEM_LETRA = Pattern.compile("[A-Za-zÀ-ÿ]");

    /**
     * Faz o parse do texto completo de uma página, retornando só as linhas-dia
     * (cabeçalho, totais e assinatura são ignorados naturalmente).
     */
    public static List<LinhaPonto> parse(String textoPagina) {
        List<LinhaPonto> out = new ArrayList<>();
        if (textoPagina == null || textoPagina.isBlank()) return out;
        for (String raw : textoPagina.split("\\r?\\n")) {
            Matcher m = LINHA_DIA.matcher(raw);
            if (m.matches()) {
                LinhaPonto linha = parseLinha(m.group(1), m.group(2), m.group(3), m.group(4));
                if (linha != null) out.add(linha);
            }
        }
        return out;
    }

    private static LinhaPonto parseLinha(String dd, String mm, String aa, String resto) {
        // 1) Marcador de dia da semana (ou "feri" de feriado), colado ao corpo.
        String marcador = null;
        for (String d : DIAS_SEMANA) {
            if (resto.startsWith(d)) { marcador = d; break; }
        }
        String corpo = marcador == null ? resto : resto.substring(marcador.length());

        // 2) Coluna DIA: verbatim ("dd/mm/aa - <dia>"); em feriado, calcula o dia real da semana.
        String diaSemana;
        if ("feri".equals(marcador)) {
            diaSemana = diaSemanaDaData(dd, mm, aa);
        } else {
            diaSemana = marcador == null ? "" : marcador;
        }
        String dia = dd + "/" + mm + "/" + aa + (diaSemana.isEmpty() ? "" : " - " + diaSemana);

        // 3) Separa deltas (TOTALDIA/BANCO, com sinal) do resto (as células ENT./SAÍ.).
        List<String> deltas = new ArrayList<>();
        Matcher dm = DELTA.matcher(corpo);
        while (dm.find()) deltas.add(dm.group());
        String celulas = DELTA.matcher(corpo).replaceAll("").trim();

        // 4) Preenche as 4 células de batida (ENT.1, SAÍ.1, ENT.2, SAÍ.2).
        String[] c = {"", "", "", ""};
        if (!celulas.isEmpty()) {
            if (TEM_LETRA.matcher(celulas).find()) {
                // Dia de status (Feriado/Falta/DISPOSI/FERNC/Atesc/…): texto repetido ×k.
                int n = celulas.length();
                String unidade = celulas;
                int k = 1;
                for (int p = 1; p <= n; p++) {
                    if (n % p == 0 && celulas.substring(0, p).repeat(n / p).equals(celulas)) {
                        unidade = celulas.substring(0, p);
                        k = n / p;
                        break;
                    }
                }
                for (int i = 0; i < Math.min(k, 4); i++) c[i] = unidade;
            } else {
                // Dia com batidas: hh:mm na ordem → ENT.1, SAÍ.1, ENT.2, SAÍ.2 (marcadores ocultos).
                Matcher bm = BATIDA.matcher(celulas);
                int i = 0;
                while (bm.find() && i < 4) c[i++] = bm.group(1);
            }
        } else if ("feri".equals(marcador)) {
            // Feriado cujo texto não veio nas células do PDF: marca "Feriado" nas quatro (requisito).
            java.util.Arrays.fill(c, "Feriado");
        }

        // 5) TOTALDIA = penúltimo delta; BANCO = último (BANCO pode não existir).
        String totalDia = "", banco = "";
        if (deltas.size() == 1) {
            banco = deltas.get(0);
        } else if (deltas.size() >= 2) {
            totalDia = deltas.get(deltas.size() - 2);
            banco = deltas.get(deltas.size() - 1);
        }

        return new LinhaPonto(dia, c[0], c[1], c[2], c[3], totalDia, banco);
    }

    /** Dia da semana (seg..dom) calculado da data — usado quando o Secullum mostra "feri". */
    private static String diaSemanaDaData(String dd, String mm, String aa) {
        try {
            LocalDate d = LocalDate.of(2000 + Integer.parseInt(aa), Integer.parseInt(mm), Integer.parseInt(dd));
            DayOfWeek dow = d.getDayOfWeek();
            return switch (dow) {
                case MONDAY -> "seg";
                case TUESDAY -> "ter";
                case WEDNESDAY -> "qua";
                case THURSDAY -> "qui";
                case FRIDAY -> "sex";
                case SATURDAY -> "sáb";
                case SUNDAY -> "dom";
            };
        } catch (RuntimeException e) {
            return "feri"; // fallback: mantém o marcador original em data inválida
        }
    }
}
