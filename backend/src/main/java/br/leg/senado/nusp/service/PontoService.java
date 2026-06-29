package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import com.lowagie.text.Document;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Ponto e Banco de Horas — Passo 1: recebe o cartão-ponto (PDF multi-página)
 * do admin, separa página a página (OpenPDF), casa cada página a um
 * operador/técnico pelo nome e disponibiliza a folha individual para download.
 */
@Service
@RequiredArgsConstructor
public class PontoService {

    private static final Logger log = LoggerFactory.getLogger(PontoService.class);

    private final PontoLoteRepository loteRepo;
    private final PontoLotePaginaRepository paginaRepo;
    private final OperadorRepository operadorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final AdministradorRepository administradorRepo;
    private final AvisoService avisoService;

    @Value("${app.files.dir}")
    private String filesDir;

    /** Subpasta (sob app.files.dir) dos arquivos de ponto. NÃO é servida em /files/ (ver SecurityConfig). */
    private static final String PONTO_DIR = "ponto";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    // ══════════════════════════════════════════════════════════════
    // Upload + separação + vínculo automático (lote em REVISÃO)
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> upload(MultipartFile arquivo, String tipoRaw,
                                      String dataInicioRaw, String dataFimRaw, String adminId) {
        String tipo = normalizeTipo(tipoRaw);
        LocalDate dataInicio = parseData(dataInicioRaw, "data_inicio");
        LocalDate dataFim = parseData(dataFimRaw, "data_fim");
        if (dataFim.isBefore(dataInicio)) {
            throw new ServiceValidationException("A data final não pode ser anterior à inicial.");
        }
        if (arquivo == null || arquivo.isEmpty()) {
            throw new ServiceValidationException("Arquivo PDF é obrigatório.");
        }

        final byte[] pdfBytes;
        try {
            pdfBytes = arquivo.getBytes();
        } catch (IOException e) {
            throw new ServiceValidationException("Não foi possível ler o arquivo enviado.");
        }

        PdfReader reader;
        try {
            reader = new PdfReader(pdfBytes);
        } catch (IOException | RuntimeException e) {
            throw new ServiceValidationException("O arquivo enviado não é um PDF válido.");
        }

        int totalPaginas = reader.getNumberOfPages();
        if (totalPaginas == 0) {
            reader.close();
            throw new ServiceValidationException("O PDF não tem páginas.");
        }

        // Cria e persiste o lote primeiro (precisamos do id para vincular as páginas).
        PontoLote lote = new PontoLote();
        lote.setTipo(tipo);
        lote.setDataInicio(dataInicio);
        lote.setDataFim(dataFim);
        lote.setTotalPaginas(totalPaginas);
        lote.setStatus("REVISAO");
        lote.setCriadoPorId(adminId);
        String originalRel = PONTO_DIR + "/originais/" + UUID.randomUUID() + ".pdf";
        lote.setArquivoOriginal(originalRel);
        salvarArquivo(originalRel, pdfBytes);
        loteRepo.save(lote);

        List<Pessoa> pessoas = carregarPessoas();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        List<PontoLotePagina> paginas = new ArrayList<>();
        try {
            for (int i = 1; i <= totalPaginas; i++) {
                byte[] pageBytes = extrairPagina(reader, i);
                String texto = "";
                try {
                    texto = extractor.getTextFromPage(i);
                } catch (Exception e) {
                    log.warn("Falha ao extrair texto da página {} do lote {}: {}", i, lote.getId(), e.getMessage());
                }

                String paginaRel = PONTO_DIR + "/paginas/" + UUID.randomUUID() + ".pdf";
                salvarArquivo(paginaRel, pageBytes);

                PontoLotePagina pg = new PontoLotePagina();
                pg.setLoteId(lote.getId());
                pg.setNumeroPagina(i);
                pg.setArquivoPagina(paginaRel);

                Pessoa match = casar(texto, pessoas);
                if (match != null) {
                    pg.setPessoaId(match.id());
                    pg.setPessoaTipo(match.tipo());
                    pg.setStatusMatch("AUTO");
                    pg.setNomeExtraido(match.nome());
                } else {
                    pg.setStatusMatch("PENDENTE");
                    pg.setNomeExtraido(extrairNomeHeuristica(texto));
                }
                paginas.add(pg);
            }
        } finally {
            reader.close();
        }

        paginaRepo.saveAll(paginas);
        return detalheLote(lote, paginas, pessoas);
    }

    // ══════════════════════════════════════════════════════════════
    // Consulta (admin)
    // ══════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarLotes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PontoLote l : loteRepo.findAllByOrderByCriadoEmDesc()) {
            long pendentes = paginaRepo.findByLoteIdOrderByNumeroPagina(l.getId()).stream()
                    .filter(p -> "PENDENTE".equals(p.getStatusMatch())).count();
            out.add(cabecalho(l, pendentes));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obterLote(String loteId) {
        PontoLote lote = buscarLote(loteId);
        List<PontoLotePagina> paginas = paginaRepo.findByLoteIdOrderByNumeroPagina(loteId);
        return detalheLote(lote, paginas, carregarPessoas());
    }

    /** Lista plana de operadores + técnicos para o dropdown de vínculo manual. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarPessoas() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pessoa p : carregarPessoas()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("nome", p.nome());
            m.put("tipo", p.tipo());
            out.add(m);
        }
        out.sort(Comparator.comparing(x -> String.valueOf(x.get("nome")).toUpperCase(Locale.ROOT)));
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // Revisão + publicação (admin)
    // ══════════════════════════════════════════════════════════════

    /** Vincula (ou desvincula, se pessoaId vazio) uma página a um operador/técnico. */
    @Transactional
    public Map<String, Object> atualizarVinculo(String loteId, String paginaId,
                                                 String pessoaId, String pessoaTipo) {
        PontoLote lote = buscarLote(loteId);
        if ("PUBLICADO".equals(lote.getStatus())) {
            throw new ServiceValidationException("Lote já publicado não pode ser alterado.");
        }
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Página não encontrada.", HttpStatus.NOT_FOUND));
        if (!pg.getLoteId().equals(loteId)) {
            throw new ServiceValidationException("Página não pertence ao lote informado.");
        }

        if (pessoaId == null || pessoaId.isBlank()) {
            pg.setPessoaId(null);
            pg.setPessoaTipo(null);
            pg.setStatusMatch("PENDENTE");
        } else {
            String tipo = pessoaTipo == null ? "" : pessoaTipo.trim().toUpperCase(Locale.ROOT);
            boolean existe = switch (tipo) {
                case "OPERADOR"      -> operadorRepo.existsById(pessoaId);
                case "TECNICO"       -> tecnicoRepo.existsById(pessoaId);
                case "ADMINISTRADOR" -> administradorRepo.existsById(pessoaId);
                default -> false;
            };
            if (!existe) throw new ServiceValidationException("Operador/técnico/administrador inválido.");
            pg.setPessoaId(pessoaId);
            pg.setPessoaTipo(tipo);
            pg.setStatusMatch("MANUAL");
        }
        paginaRepo.save(pg);

        List<PontoLotePagina> paginas = paginaRepo.findByLoteIdOrderByNumeroPagina(loteId);
        return detalheLote(lote, paginas, carregarPessoas());
    }

    @Transactional
    public Map<String, Object> publicar(String loteId, boolean emitirAviso) {
        PontoLote lote = buscarLote(loteId);
        if ("PUBLICADO".equals(lote.getStatus())) {
            throw new ServiceValidationException("Lote já está publicado.");
        }
        lote.setStatus("PUBLICADO");
        lote.setPublicadoEm(LocalDateTime.now());
        loteRepo.save(lote);

        List<PontoLotePagina> paginas = paginaRepo.findByLoteIdOrderByNumeroPagina(loteId);
        if (emitirAviso) criarAvisosPessoais(lote, paginas);
        return detalheLote(lote, paginas, carregarPessoas());
    }

    /**
     * Ao publicar, dispara UM aviso PESSOAL (multi-alvo) avisando cada pessoa
     * com folha no lote — operador, técnico ou administrador. Some quando a
     * pessoa marca ciência. Páginas pendentes (sem pessoa) são ignoradas.
     */
    private void criarAvisosPessoais(PontoLote lote, List<PontoLotePagina> paginas) {
        List<AvisoService.DestinatarioAviso> destinatarios = new ArrayList<>();
        for (PontoLotePagina p : paginas) {
            if (p.getPessoaId() == null) continue;
            PapelPessoa papel = papelDePessoaTipo(p.getPessoaTipo());
            if (papel != null) destinatarios.add(new AvisoService.DestinatarioAviso(p.getPessoaId(), papel));
        }
        if (destinatarios.isEmpty()) return;
        avisoService.criarPessoalIndividual(destinatarios, mensagemFolhaPublicada(lote), lote.getCriadoPorId());
    }

    private PapelPessoa papelDePessoaTipo(String pessoaTipo) {
        if (pessoaTipo == null) return null;
        return switch (pessoaTipo.trim().toUpperCase(Locale.ROOT)) {
            case "OPERADOR" -> PapelPessoa.OPERADOR;
            case "TECNICO" -> PapelPessoa.TECNICO;
            case "ADMINISTRADOR" -> PapelPessoa.ADMIN;
            default -> null;
        };
    }

    private String mensagemFolhaPublicada(PontoLote lote) {
        DateTimeFormatter br = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String periodo = "SEMANAL".equals(lote.getTipo()) ? "semanal" : "mensal";
        return "Sua folha de ponto " + periodo + " (" + lote.getDataInicio().format(br)
                + " a " + lote.getDataFim().format(br) + ") foi publicada. "
                + "Acesse \"Minhas Folhas\" para visualizá-la.";
    }

    // ══════════════════════════════════════════════════════════════
    // Operador / técnico
    // ══════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Map<String, Object>> minhasFolhas(String pessoaId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : paginaRepo.findFolhasPublicadasByPessoa(pessoaId)) {
            PontoLotePagina p = (PontoLotePagina) row[0];
            PontoLote l = (PontoLote) row[1];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("tipo", l.getTipo());
            m.put("data_inicio", l.getDataInicio().toString());
            m.put("data_fim", l.getDataFim().toString());
            m.put("publicado_em", l.getPublicadoEm() != null ? l.getPublicadoEm().toString() : null);
            out.add(m);
        }
        return out;
    }

    /** Download da folha por um operador/técnico (só a própria) ou admin (qualquer). */
    @Transactional(readOnly = true)
    public ArquivoPonto baixarFolha(String paginaId, String solicitanteId, String role) {
        FolhaAcesso fa = checarAcessoFolha(paginaId, solicitanteId, role);
        PontoLotePagina pg = fa.pagina();
        PontoLote lote = fa.lote();
        String nome = "ponto-" + lote.getTipo().toLowerCase(Locale.ROOT)
                + "-" + lote.getDataInicio() + "_a_" + lote.getDataFim() + ".pdf";
        return new ArquivoPonto(lerArquivo(pg.getArquivoPagina()), nome);
    }

    /**
     * Dados da folha (parse do PDF Secullum) para a tela de retificação: uma linha
     * por dia, com o texto VERBATIM em cada uma das 7 colunas. Mesma checagem de
     * dono do download.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> dadosFolha(String paginaId, String solicitanteId, String role) {
        FolhaAcesso fa = checarAcessoFolha(paginaId, solicitanteId, role);
        PontoLotePagina pg = fa.pagina();
        PontoLote lote = fa.lote();

        byte[] bytes = lerArquivo(pg.getArquivoPagina());
        PdfReader reader;
        try {
            reader = new PdfReader(bytes);
        } catch (IOException | RuntimeException e) {
            throw new ServiceValidationException("Não foi possível ler o PDF da folha.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        String texto;
        try {
            texto = new PdfTextExtractor(reader).getTextFromPage(1);
        } catch (Exception e) {
            throw new ServiceValidationException("Não foi possível extrair os dados da folha.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            reader.close();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SecullumFolhaParser.LinhaPonto l : SecullumFolhaParser.parse(texto)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dia", l.dia());
            m.put("ent1", l.ent1());
            m.put("sai1", l.sai1());
            m.put("ent2", l.ent2());
            m.put("sai2", l.sai2());
            m.put("total_dia", l.totalDia());
            m.put("banco", l.banco());
            rows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", pg.getId());
        out.put("tipo", lote.getTipo());
        out.put("data_inicio", lote.getDataInicio().toString());
        out.put("data_fim", lote.getDataFim().toString());
        out.put("linhas", rows);
        return out;
    }

    private record FolhaAcesso(PontoLotePagina pagina, PontoLote lote) {}

    /** Localiza página + lote garantindo o acesso do solicitante (dono de lote publicado, ou admin). */
    private FolhaAcesso checarAcessoFolha(String paginaId, String solicitanteId, String role) {
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Folha não encontrada.", HttpStatus.NOT_FOUND));
        PontoLote lote = buscarLote(pg.getLoteId());
        if (!"administrador".equals(role)) {
            if (pg.getPessoaId() == null || !pg.getPessoaId().equals(solicitanteId)) {
                throw new ServiceValidationException("Acesso negado a esta folha.", HttpStatus.FORBIDDEN);
            }
            if (!"PUBLICADO".equals(lote.getStatus())) {
                throw new ServiceValidationException("Folha indisponível.", HttpStatus.NOT_FOUND);
            }
        }
        return new FolhaAcesso(pg, lote);
    }

    /** Preview de uma página (admin, durante a revisão). */
    @Transactional(readOnly = true)
    public ArquivoPonto previewPagina(String paginaId) {
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Página não encontrada.", HttpStatus.NOT_FOUND));
        return new ArquivoPonto(lerArquivo(pg.getArquivoPagina()), "pagina-" + pg.getNumeroPagina() + ".pdf");
    }

    public record ArquivoPonto(byte[] conteudo, String nomeArquivo) {}

    // ══════════════════════════════════════════════════════════════
    // PDF — separação e texto (OpenPDF)
    // ══════════════════════════════════════════════════════════════

    private byte[] extrairPagina(PdfReader reader, int pageNum) {
        Document doc = new Document();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            PdfCopy copy = new PdfCopy(doc, baos);
            doc.open();
            copy.addPage(copy.getImportedPage(reader, pageNum));
            doc.close();
        } catch (Exception e) {
            throw new ServiceValidationException("Falha ao separar a página " + pageNum + " do PDF.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return baos.toByteArray();
    }

    // ══════════════════════════════════════════════════════════════
    // Pareamento por nome
    // ══════════════════════════════════════════════════════════════

    private record Pessoa(String id, String tipo, String nome, String nomeNorm) {}

    private List<Pessoa> carregarPessoas() {
        List<Pessoa> out = new ArrayList<>();
        for (Operador o : operadorRepo.findAll()) {
            String norm = normalizar(o.getNomeCompleto());
            if (!norm.isBlank()) out.add(new Pessoa(o.getId(), "OPERADOR", o.getNomeCompleto(), norm));
        }
        for (Tecnico t : tecnicoRepo.findAll()) {
            String norm = normalizar(t.getNomeCompleto());
            if (!norm.isBlank()) out.add(new Pessoa(t.getId(), "TECNICO", t.getNomeCompleto(), norm));
        }
        // Alguns terceirizados (supervisor técnico, controlador) são admins do sistema.
        for (Administrador a : administradorRepo.findAll()) {
            String norm = normalizar(a.getNomeCompleto());
            if (!norm.isBlank()) out.add(new Pessoa(a.getId(), "ADMINISTRADOR", a.getNomeCompleto(), norm));
        }
        // Do nome mais longo para o mais curto: evita um nome curto casar dentro de um maior.
        out.sort((a, b) -> Integer.compare(b.nomeNorm().length(), a.nomeNorm().length()));
        return out;
    }

    /**
     * Casa o texto da página contra a lista de pessoas: vínculo automático quando
     * o nome completo (normalizado) aparece como sequência de tokens no texto.
     */
    private Pessoa casar(String textoPagina, List<Pessoa> pessoas) {
        String alvo = " " + normalizar(textoPagina) + " ";
        for (Pessoa p : pessoas) { // já ordenado do nome mais longo para o mais curto
            if (alvo.contains(" " + p.nomeNorm() + " ")) return p;
        }
        return null;
    }

    /** Uppercase sem acentos, só [A-Z0-9], espaços colapsados. */
    private String normalizar(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return t.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    /**
     * Melhor esforço para exibir o nome de uma página NÃO casada (apenas uma dica
     * na tela de revisão — o admin confere abrindo o preview). No cartão Secullum,
     * a assinatura traz "&lt;NOME&gt; PLANSUL PLANEJAMENTO ...".
     */
    private String extrairNomeHeuristica(String texto) {
        if (texto == null || texto.isBlank()) return null;
        int idx = texto.toUpperCase(Locale.ROOT).lastIndexOf("PLANSUL");
        if (idx <= 0) return null;
        String antes = texto.substring(0, idx).replaceAll("[_\\r\\n]+", " ").trim();
        String[] toks = antes.split("\\s+");
        // Na assinatura o nome vem em MAIÚSCULAS logo antes de "PLANSUL".
        // Pegamos a sequência final de tokens só-letras e em caixa-alta (isola o nome
        // do texto legal em caixa mista que o antecede).
        Deque<String> nome = new ArrayDeque<>();
        for (int k = toks.length - 1; k >= 0 && nome.size() < 8; k--) {
            String tk = toks[k];
            boolean nomeLike = tk.matches("[A-Za-zÀ-ÿ]{2,}") && tk.equals(tk.toUpperCase(Locale.ROOT));
            if (nomeLike) nome.addFirst(tk);
            else if (!nome.isEmpty()) break;
        }
        if (nome.size() < 2) return null;
        String r = String.join(" ", nome);
        return r.length() > 200 ? r.substring(0, 200) : r;
    }

    // ══════════════════════════════════════════════════════════════
    // Arquivos
    // ══════════════════════════════════════════════════════════════

    private void salvarArquivo(String relPath, byte[] bytes) {
        try {
            Path dest = Paths.get(filesDir).resolve(relPath);
            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes);
        } catch (IOException e) {
            log.error("Erro ao salvar arquivo de ponto {}: {}", relPath, e.getMessage());
            throw new ServiceValidationException("Erro ao salvar arquivo no servidor.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private byte[] lerArquivo(String relPath) {
        try {
            return Files.readAllBytes(Paths.get(filesDir).resolve(relPath));
        } catch (IOException e) {
            log.error("Erro ao ler arquivo de ponto {}: {}", relPath, e.getMessage());
            throw new ServiceValidationException("Arquivo não disponível no servidor.", HttpStatus.NOT_FOUND);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Montagem de resposta + helpers
    // ══════════════════════════════════════════════════════════════

    private Map<String, Object> detalheLote(PontoLote lote, List<PontoLotePagina> paginas, List<Pessoa> pessoas) {
        Map<String, String> nomePorId = new HashMap<>();
        for (Pessoa p : pessoas) nomePorId.put(p.id(), p.nome());

        long pendentes = paginas.stream().filter(p -> "PENDENTE".equals(p.getStatusMatch())).count();
        Map<String, Object> m = cabecalho(lote, pendentes);

        List<Map<String, Object>> pgs = new ArrayList<>();
        for (PontoLotePagina p : paginas) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId());
            pm.put("numero_pagina", p.getNumeroPagina());
            pm.put("nome_extraido", p.getNomeExtraido());
            pm.put("pessoa_id", p.getPessoaId());
            pm.put("pessoa_tipo", p.getPessoaTipo());
            pm.put("pessoa_nome", p.getPessoaId() != null ? nomePorId.get(p.getPessoaId()) : null);
            pm.put("status_match", p.getStatusMatch());
            pgs.add(pm);
        }
        m.put("paginas", pgs);
        return m;
    }

    private Map<String, Object> cabecalho(PontoLote l, long pendentes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("tipo", l.getTipo());
        m.put("data_inicio", l.getDataInicio().toString());
        m.put("data_fim", l.getDataFim().toString());
        m.put("status", l.getStatus());
        m.put("total_paginas", l.getTotalPaginas());
        m.put("pendentes", pendentes);
        m.put("criado_em", l.getCriadoEm() != null ? l.getCriadoEm().toString() : null);
        m.put("publicado_em", l.getPublicadoEm() != null ? l.getPublicadoEm().toString() : null);
        return m;
    }

    private PontoLote buscarLote(String loteId) {
        return loteRepo.findById(loteId)
                .orElseThrow(() -> new ServiceValidationException("Lote não encontrado.", HttpStatus.NOT_FOUND));
    }

    private String normalizeTipo(String tipo) {
        String t = tipo == null ? "" : tipo.trim().toUpperCase(Locale.ROOT);
        if (!t.equals("SEMANAL") && !t.equals("MENSAL")) {
            throw new ServiceValidationException("Tipo inválido (use SEMANAL ou MENSAL).");
        }
        return t;
    }

    private LocalDate parseData(String raw, String campo) {
        try {
            return LocalDate.parse(raw.trim(), ISO);
        } catch (Exception e) {
            throw new ServiceValidationException("Data inválida em " + campo + " (use AAAA-MM-DD).");
        }
    }
}
