package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.repository.SalaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Monitora a Agenda Legislativa do Senado Federal em tempo real.
 *
 * Fontes:
 * - Comissões: API Dados Abertos (XML) — legis.senado.leg.br/dadosabertos/comissao/agenda/YYYYMMDD
 * - Plenário Principal: scraping da página de atividade — www25.senado.leg.br/web/atividade
 *
 * Polling a cada 30 segundos. Mudanças são enviadas via SSE aos operadores conectados.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgendaLegislativaService {

    private final SalaRepository salaRepository;

    // ── Cache em memória ────────────────────────────────────────
    private volatile List<Map<String, Object>> cacheComissoes = Collections.emptyList();
    private volatile List<Map<String, Object>> cachePlenario = Collections.emptyList();
    private volatile String lastHashComissoes = "";
    private volatile String lastHashPlenario = "";

    // ── SSE emitters ────────────────────────────────────────────
    private final CopyOnWriteArrayList<EmitterEntry> emitters = new CopyOnWriteArrayList<>();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String COMISSAO_API = "https://legis.senado.leg.br/dadosabertos/comissao/agenda/";
    private static final String ATIVIDADE_URL = "https://www25.senado.leg.br/web/atividade";
    private static final Pattern PLENARIO_NUM_PATTERN = Pattern.compile("Plen[aá]rio\\s+n[ºo°]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // Mapeamento: número do plenário → sala_id (carregado na primeira execução)
    private volatile Map<Integer, Integer> plenarioToSalaId = null;

    // ══ Polling — a cada 30 segundos ════════════════════════════

    @Scheduled(fixedRate = 30_000, initialDelay = 5_000)
    public void poll() {
        try {
            carregarMapeamentoSeNecessario();
            String hoje = LocalDate.now().format(DATE_FMT);

            // 1. Comissões (API XML)
            List<Map<String, Object>> novasComissoes = fetchComissoes(hoje);
            String hashComissoes = String.valueOf(novasComissoes.hashCode());
            boolean comissoesChanged = !hashComissoes.equals(lastHashComissoes);
            if (comissoesChanged) {
                cacheComissoes = novasComissoes;
                lastHashComissoes = hashComissoes;
                log.info("Agenda comissões atualizada: {} reuniões", novasComissoes.size());
            }

            // 2. Plenário Principal (scraping)
            List<Map<String, Object>> novasPlenario = fetchPlenario();
            String hashPlenario = String.valueOf(novasPlenario.hashCode());
            boolean plenarioChanged = !hashPlenario.equals(lastHashPlenario);
            if (plenarioChanged) {
                cachePlenario = novasPlenario;
                lastHashPlenario = hashPlenario;
                log.info("Agenda plenário principal atualizada: {} sessões", novasPlenario.size());
            }

            // 3. Broadcast via SSE sempre (atualiza timestamp para os operadores)
            broadcast();
        } catch (Exception e) {
            log.error("Erro ao atualizar agenda legislativa", e);
        }
    }

    // ══ API pública ═════════════════════════════════════════════

    /** Reuniões de comissões de hoje filtradas por sala_id */
    public List<Map<String, Object>> getAgendaPorSala(int salaId) {
        return cacheComissoes.stream()
                .filter(r -> salaId == toInt(r.get("sala_id")))
                .collect(Collectors.toList());
    }

    /** Todas as reuniões de comissões de hoje */
    public List<Map<String, Object>> getAgendaComissoes() {
        return cacheComissoes;
    }

    /** Sessões plenárias de hoje (Plenário Principal) */
    public List<Map<String, Object>> getAgendaPlenario() {
        return cachePlenario;
    }

    /** Subscribir via SSE — retorna emitter que recebe atualizações */
    public SseEmitter subscribe(Integer salaId, boolean plenarioPrincipal) {
        SseEmitter emitter = new SseEmitter(0L); // sem timeout
        EmitterEntry entry = new EmitterEntry(emitter, salaId, plenarioPrincipal);

        emitters.add(entry);
        emitter.onCompletion(() -> emitters.remove(entry));
        emitter.onTimeout(() -> emitters.remove(entry));
        emitter.onError(e -> emitters.remove(entry));

        // Enviar dados iniciais
        try {
            Map<String, Object> data = buildDataForEntry(entry);
            emitter.send(SseEmitter.event().name("agenda").data(data));
        } catch (Exception e) {
            emitters.remove(entry);
        }

        return emitter;
    }

    // ══ Fetch — Comissões (API XML) ═════════════════════════════

    private List<Map<String, Object>> fetchComissoes(String dataFormatada) {
        try {
            String xml = httpGet(COMISSAO_API + dataFormatada);
            if (xml == null || xml.isBlank()) return Collections.emptyList();
            return parseXmlComissoes(xml);
        } catch (Exception e) {
            log.warn("Falha ao buscar agenda de comissões: {}", e.getMessage());
            return cacheComissoes; // manter cache anterior
        }
    }

    private List<Map<String, Object>> parseXmlComissoes(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var builder = factory.newDocumentBuilder();
        var doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        NodeList reunioes = doc.getElementsByTagName("reuniao");
        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 0; i < reunioes.getLength(); i++) {
            Element reuniao = (Element) reunioes.item(i);
            Map<String, Object> item = new LinkedHashMap<>();

            item.put("codigo", getTag(reuniao, "codigo"));
            item.put("titulo", getTag(reuniao, "titulo"));
            item.put("situacao", getTag(reuniao, "situacao"));

            // Comissão
            Element colegiado = getFirstElement(reuniao, "colegiadoCriador");
            if (colegiado != null) {
                item.put("comissao_sigla", getTag(colegiado, "sigla"));
                item.put("comissao_nome", getTag(colegiado, "nome"));
            }

            // Horário
            String dataInicio = getTag(reuniao, "dataInicio");
            if (dataInicio != null && dataInicio.contains("T")) {
                String horaParte = dataInicio.split("T")[1];
                if (horaParte.length() >= 5) {
                    item.put("horario", horaParte.substring(0, 5));
                }
            }
            String obsHorario = getTag(reuniao, "observacaoHorario");
            if (obsHorario != null && !obsHorario.isBlank()) {
                item.put("observacao_horario", obsHorario);
            }

            // Local e mapeamento para sala
            String local = getTag(reuniao, "local");
            item.put("local", local);

            Integer salaId = mapearLocalParaSala(local);
            item.put("sala_id", salaId);

            // Tipo
            Element tipo = getFirstElement(reuniao, "tipo");
            if (tipo != null) {
                item.put("tipo_descricao", getTag(tipo, "descricao"));
            }

            // Tipo presença
            item.put("tipo_presenca", getTag(reuniao, "tipoPresenca"));

            // Só incluir se mapeou para uma sala do sistema
            if (salaId != null) {
                result.add(item);
            }
        }

        return result;
    }

    // ══ Fetch — Plenário Principal (scraping) ═══════════════════

    private List<Map<String, Object>> fetchPlenario() {
        try {
            String html = httpGet(ATIVIDADE_URL);
            if (html == null || html.isBlank()) return Collections.emptyList();
            return parsePlenarioHtml(html);
        } catch (Exception e) {
            log.warn("Falha ao buscar agenda do plenário: {}", e.getMessage());
            return cachePlenario;
        }
    }

    /**
     * Parse da página de atividade para extrair sessões plenárias.
     *
     * Estrutura HTML real (cada sessão é um bloco "painel painel-base"):
     *   <strong>Senado Federal</strong>          ← identifica como plenário
     *   <span class="painel-corpo-hora"> 10<small>h00</small> </span>
     *   <span>Sessão Deliberativa Ordinária </span>
     *   <span class="label label-warning">Em andamento</span>
     *   <small class="descTruncadaDescricao">Descrição...</small>
     */
    private List<Map<String, Object>> parsePlenarioHtml(String html) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Dividir em blocos de painel (cada sessão/comissão é um bloco)
        String[] paineis = html.split("painel painel-base painel-base-azul");

        int idx = 0;
        for (String painel : paineis) {
            // Só processar painéis do Plenário (cabeçalho contém "Senado Federal" sem link de comissão)
            if (!painel.contains("<strong>Senado Federal</strong>")) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("codigo", "PLEN-" + (++idx));
            item.put("local", "Senado Federal");
            item.put("tipo_descricao", "Sessão Plenária");

            // Horário: "10<small>h00</small>" ou "14<small>h00</small>"
            Matcher horaMatcher = Pattern.compile("painel-corpo-hora[^>]*>\\s*(\\d{1,2})<small>h(\\d{2})</small>").matcher(painel);
            if (horaMatcher.find()) {
                String hora = horaMatcher.group(1).length() == 1 ? "0" + horaMatcher.group(1) : horaMatcher.group(1);
                item.put("horario", hora + ":" + horaMatcher.group(2));
            }

            // Título da sessão: "<span>36ª</span> <span>Sessão Especial </span>" ou "<span>Sessão Deliberativa Ordinária </span>"
            Matcher tituloMatcher = Pattern.compile("<span>([^<]*Sess[ãa]o[^<]*)</span>", Pattern.CASE_INSENSITIVE).matcher(painel);
            StringBuilder titulo = new StringBuilder();
            // Capturar também o número da sessão (ex: "36ª")
            Matcher numMatcher = Pattern.compile("<span>(\\d+[ªº])</span>").matcher(painel);
            if (numMatcher.find()) {
                titulo.append(numMatcher.group(1)).append(" ");
            }
            if (tituloMatcher.find()) {
                titulo.append(tituloMatcher.group(1).trim());
            }
            item.put("titulo", titulo.toString().trim());

            // Status: <span class="label label-xxx">Status</span>
            Matcher statusMatcher = Pattern.compile("class=\"label label-[^\"]*\"[^>]*>([^<]+)<").matcher(painel);
            if (statusMatcher.find()) {
                item.put("situacao", statusMatcher.group(1).trim());
            }

            // Descrição
            Matcher descMatcher = Pattern.compile("descTruncadaDescricao\">([^<]+)<").matcher(painel);
            if (descMatcher.find()) {
                item.put("descricao", descMatcher.group(1).trim());
            }

            result.add(item);
        }

        return result;
    }

    // ══ SSE — Broadcast ═════════════════════════════════════════

    private void broadcast() {
        List<EmitterEntry> dead = new ArrayList<>();
        for (EmitterEntry entry : emitters) {
            try {
                Map<String, Object> data = buildDataForEntry(entry);
                entry.emitter.send(SseEmitter.event().name("agenda").data(data));
            } catch (Exception e) {
                dead.add(entry);
            }
        }
        emitters.removeAll(dead);
    }

    private Map<String, Object> buildDataForEntry(EmitterEntry entry) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (entry.plenarioPrincipal) {
            data.put("reunioes", cachePlenario);
            data.put("tipo", "plenario_principal");
        } else if (entry.salaId != null) {
            data.put("reunioes", getAgendaPorSala(entry.salaId));
            data.put("tipo", "comissao");
            data.put("sala_id", entry.salaId);
        } else {
            data.put("reunioes", cacheComissoes);
            data.put("tipo", "todas");
        }
        data.put("atualizado_em", java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDateTime().toString());
        return data;
    }

    // ══ Mapeamento plenário → sala ══════════════════════════════

    private void carregarMapeamentoSeNecessario() {
        if (plenarioToSalaId != null) return;

        Map<Integer, Integer> mapa = new HashMap<>();
        List<Sala> salas = salaRepository.findAtivasOrdenadas();
        Pattern numPattern = Pattern.compile("Plen[aá]rio\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

        for (Sala sala : salas) {
            Matcher m = numPattern.matcher(sala.getNome());
            if (m.find()) {
                int num = Integer.parseInt(m.group(1));
                mapa.put(num, sala.getId());
            }
        }

        plenarioToSalaId = mapa;
        log.info("Mapeamento plenário → sala carregado: {}", mapa);
    }

    /** Extrai nº do plenário do campo 'local' e mapeia para sala_id */
    private Integer mapearLocalParaSala(String local) {
        if (local == null || plenarioToSalaId == null) return null;

        Matcher m = PLENARIO_NUM_PATTERN.matcher(local);
        if (m.find()) {
            int num = Integer.parseInt(m.group(1));
            return plenarioToSalaId.get(num);
        }
        return null;
    }

    // ══ HTTP ════════════════════════════════════════════════════

    private String httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/xml, text/html, */*")
                    .header("User-Agent", "NUSP-SenadoApp/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                log.warn("HTTP {} ao buscar {}", response.statusCode(), url);
                return null;
            }
        } catch (Exception e) {
            log.warn("Erro HTTP ao buscar {}: {}", url, e.getMessage());
            return null;
        }
    }

    // ══ XML helpers ═════════════════════════════════════════════

    private String getTag(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        Node node = nodes.item(0);
        return node.getTextContent() != null ? node.getTextContent().trim() : null;
    }

    private Element getFirstElement(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return -1;
    }

    // ══ Emitter entry ═══════════════════════════════════════════

    private record EmitterEntry(SseEmitter emitter, Integer salaId, boolean plenarioPrincipal) {}
}
