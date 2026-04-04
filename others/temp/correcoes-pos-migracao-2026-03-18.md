# Correções Pós-Migração — 18-19/03/2026

Resumo de todas as correções e melhorias realizadas na sessão de ajustes do sistema NUSP (Java/Angular).

---

## 1. Interface — Tela de Login

- Título alterado de "NUSP / Senado Federal" para "Acessar o Sistema" com subtítulo "Informe suas credenciais"
- Label do campo de usuário: "Usuário ou e-mail" → "Login" com placeholder `seu.usuario`
- Placeholder do campo senha: `********`
- Removido logo quebrado do header da tela de login

## 2. Interface — Header (todas as páginas)

- Logo do Senado corrigido: imagens copiadas de `src/assets/imgs/` para `public/assets/imgs/` (Angular servia de `public/`)
- Nome do usuário logado visível (resolve `nome || name || username` — login retorna `nome`, whoami retorna `name`)
- Layout: logo à esquerda, nome + botão "Sair" à direita, sem `max-width` limitando
- Tamanho do logo ajustado para 180x45px

## 3. Interface — Dashboard Admin

- Cards: de 4 para 6 em grid 3x2, igual ao original (Página Inicial dos Operadores, Cadastro de Operador, Escala Semanal, Operações de Áudio, Edição de Formulários, Dashboard Metabase)
- "Escala Semanal" e "Dashboard Metabase (teste)" como cards desabilitados (sem rota ainda)

## 4. Tabela de Verificação de Plenários (Admin)

- Coluna **Duração** adicionada (calculada entre início e término)
- Campos em branco corrigidos: `operador` → `operador_nome`, `inicio` → `hora_inicio_testes`, `termino` → `hora_termino_testes`
- **CLOB Oracle**: `descricao_falha` retornava `oracle.sql.CLOB@xxx` — criado `NativeQueryUtils.str()` com tratamento de CLOB
- Accordion de itens: carregamento via API (`/api/admin/checklist/detalhe`) + `chkRows.set([...])` para forçar change detection do Angular
- Status Ok/Falha: adicionado `CASE WHEN EXISTS` na query SQL para calcular status na listagem

## 5. Seletor de Itens por Página

- Componente `PaginationComponent` ganhou dropdown (10, 20, 30, 50, 100) em todas as tabelas
- Limite padrão alterado de 25 para 10
- Evento `limitChange` emitido e tratado em todos os componentes

## 6. Filtros Tipo Excel

- Componente `ColumnFilterComponent` criado com: classificação crescente/decrescente, filtro por período (datas), checkboxes de valores únicos (via `meta.distinct`), busca dentro dos valores, botão limpar
- Integrado em todas as tabelas: Admin Dashboard (operadores, checklists), Admin Operações (sessões, entradas, anormalidades), Home Operador (checklists, operações)
- Backend já retornava `distinct` no `meta` — zero alteração no backend

## 7. Páginas de Detalhe (Admin — Somente Leitura)

- **Checklist**: `/admin/checklist/detalhe` — Identificação, Itens Verificados (Ok/Falha com ícone), Observações, Responsável, botões Fechar/Imprimir
- **Operação**: `/admin/operacao/detalhe` — Local, Atividade Legislativa, Evento, Horários (grid 4 cols), Evento Encerrado, USB, Observações, Anormalidade, Operador
- **Anormalidade**: `/admin/anormalidade/detalhe` — Dados do Evento, Detalhes da Anormalidade, Impactos condicionais (prejuízo, reclamação, manutenção, resolvida), Observações do Supervisor (editável por `emanoel`), Observações do Chefe (editável por `evandrop`)

## 8. Página de Operações Admin — Reformulação Completa

- Checkbox "Agrupar por local" (alterna entre sessões agrupadas e lista plana de entradas)
- Dropdowns Ano/Mês/Gerar RDS com nomes de meses em pt-BR e download XLSX
- Accordion funcional: novo endpoint `GET /api/admin/dashboard/operacoes/entradas-sessao` para carregar entradas de uma sessão
- Duplo-clique na sublinha abre detalhe da operação
- Dropdown "Selecione a extensão" (PDF/DOCX) + botão "Gerar Relatório"
- Tabela de Anormalidades: adicionadas colunas Prejuízo e Reclamação, botão "Detalhes"

## 9. Relatórios PDF/DOCX

- `ApiService.downloadReport()`: método genérico que respeita o formato (PDF abre inline, DOCX baixa arquivo)
- Corrigido `openPdfInline` que sobrescrevia `format: 'pdf'` sempre
- Mapeamento de campos nos controllers de relatório (os nomes do `DashboardQueryHelper` eram diferentes dos que o `ReportPdfService` esperava):
  - Operações sessões: `sala_nome` → `sala`, `criado_por_nome` → `autor`, `checklist_do_dia_ok` → `verificacao`, entradas carregadas
  - Operações entradas: `operador_nome` → `operador`, `tipo_evento` → `tipo`, `nome_evento` → `evento`, etc.
  - Checklists: `operador_nome` → `operador`, horários, duração calculada, itens carregados
  - Anormalidades: `sala_nome` → `sala`, `descricao_anormalidade` → `descricao`, `resolvida_pelo_operador` → `solucionada`

## 10. Coluna Solucionada (Anormalidades)

- Corrigido: o sistema original usava `resolvida_pelo_operador`, não `data_solucao`
- Campo `RESOLVIDA_PELO_OPERADOR` adicionado à query `listAnormalidades`
- Frontend e relatório PDF corrigidos

## 11. Limpeza DRY — Frontend

- **`core/helpers/table.helpers.ts`**: extraídos `getDistinct()`, `buildFilters()`, `buildReportParams()`, `mesNome()` (antes duplicados em 3 componentes)
- **`styles.scss`**: ~45 classes de dashboard e detalhe movidas para global (section-header, btn-report, table-container, accordion-row, sub-table, detalhe-card, field-value, btn-fechar, btn-imprimir, etc.)
- 3 dashboards e 3 páginas de detalhe ficaram com CSS mínimo (só estilos específicos)

## 12. Limpeza DRY — Backend

- **`NativeQueryUtils.java`**: `str()` (com tratamento CLOB), `num()`, `boolVal()` centralizados (antes duplicados em 5-6 services)
- `DashboardQueryHelper.convertValue()` delegado para `NativeQueryUtils.str()`
- Todos os services atualizados para delegar para `NativeQueryUtils`

## 13. Regra DRY no CLAUDE.md

- Adicionada como **Regra #1 — Prioridade Máxima** com checklist obrigatório
- CLAUDE.md reescrito de 604 para 192 linhas (removido histórico de fases, mantido apenas o operacional)

## 14. Modo Edição — Formulários do Operador

### Checklist (`checklist-wizard.component.ts`)
- Detecta `checklist_id` na URL → modo edição
- Abre em **somente leitura** (itens com Ok/Falha, observações, data/local como texto)
- Botão "Editar" → modo edição (data, local, itens, observações editáveis)
- Salva via `PUT /api/forms/checklist/editar` → volta para somente leitura
- Backend: `sala_id`, `item_tipo_id`, `tipo_widget` adicionados ao endpoint `/api/operador/checklist/detalhe`

### Operação (`operacao-form.component.ts`)
- Detecta `entrada_id` na URL `/operacao/edit` → modo edição
- Abre em **somente leitura** (todos os campos readonly, operador responsável visível)
- Botão "Editar" → modo edição com regras condicionais:
  - Sala: sempre readonly
  - Evento encerrado (radio): sempre disabled
  - Hora entrada: readonly se 1º operador (espelha hora_inicio)
  - Hora saída: readonly se evento encerrado (espelha hora_fim)
  - Anormalidade: disabled se já era `true`
- Se anormalidade muda de `false` → `true`, redireciona para formulário de anormalidade
- Salva via `PUT /api/operacao/audio/editar-entrada` → volta para somente leitura
- Backend: `sala_id`, `registro_id`, `comissao_id`, `comissao_nome`, `ordem` adicionados ao endpoint `/api/operador/operacao/detalhe`

### Anormalidade (detalhe somente leitura)
- Reutiliza `anormalidade-detalhe.component.ts` (DRY): detecta rota admin vs operador e usa endpoint correto
- Seção de observações administrativas (supervisor/chefe) visível somente para admin
- Rota `/anormalidade/detalhe` adicionada para operador
- Campo `anormalidade_id` adicionado à query de "minhas operações" (LEFT JOIN OPR_ANORMALIDADE)

## 15. Correções de Bugs do Backend

- **Ownership queries**: `SELECT c.CRIADO_POR` (coluna única) retorna `List<Object>`, não `List<Object[]>` — corrigido nos 3 endpoints do operador (checklist, operação, anormalidade)
- **`Map.of()` imutável**: `Map.of()` retorna mapa imutável, e controllers faziam `result.put("ok", true)` → `UnsupportedOperationException`. Corrigido em `ChecklistService` e `OperacaoService` (5 retornos) com `new LinkedHashMap<>(Map.of(...))`
- **Enum converters**: `@Enumerated(EnumType.STRING)` usava `Enum.valueOf()` que exige nome exato da constante (`MATUTINO`), mas banco armazena valor (`Matutino`). Criados 4 `AttributeConverter` com `autoApply = true` (Turno, StatusResposta, TipoWidget, TipoEvento) e removidos `@Enumerated` das entidades
- **`GlobalExceptionHandler`**: adicionado `ex.printStackTrace()` para logar exceções no console (antes engolia silenciosamente)

---

## Arquivos Criados

| Arquivo | Descrição |
|---|---|
| `frontend/src/app/shared/components/column-filter.component.ts` | Filtro tipo Excel por coluna |
| `frontend/src/app/core/helpers/table.helpers.ts` | Helpers compartilhados (getDistinct, buildFilters, etc.) |
| `frontend/src/app/pages/admin/checklist-detalhe.component.ts` | Detalhe checklist (admin, read-only) |
| `frontend/src/app/pages/admin/operacao-detalhe.component.ts` | Detalhe operação (admin, read-only) |
| `frontend/src/app/pages/admin/anormalidade-detalhe.component.ts` | Detalhe anormalidade (admin+operador, observações editáveis) |
| `backend/.../service/NativeQueryUtils.java` | Helpers de query nativa (str, num, boolVal) |
| `backend/.../enums/TurnoConverter.java` | AttributeConverter para enum Turno |
| `backend/.../enums/StatusRespostaConverter.java` | AttributeConverter para enum StatusResposta |
| `backend/.../enums/TipoWidgetConverter.java` | AttributeConverter para enum TipoWidget |
| `backend/.../enums/TipoEventoConverter.java` | AttributeConverter para enum TipoEvento |

## Arquivos Modificados (principais)

| Arquivo | Tipo de Alteração |
|---|---|
| `frontend/src/styles.scss` | ~45 classes globais adicionadas (dashboard + detalhe) |
| `frontend/src/app/pages/home/checklist-wizard.component.ts` | Modo edição (readonly → editar → salvar) |
| `frontend/src/app/pages/home/operacao-form.component.ts` | Modo edição com regras condicionais |
| `frontend/src/app/pages/home/home.component.ts` | Filtros, links corrigidos |
| `frontend/src/app/pages/admin/admin-dashboard.component.ts` | Filtros, cards, accordion, relatórios |
| `frontend/src/app/pages/admin/admin-operacoes.component.ts` | Reformulação completa |
| `frontend/src/app/shared/components/pagination.component.ts` | Seletor de itens por página |
| `frontend/src/app/core/services/api.service.ts` | `downloadReport()` adicionado |
| `frontend/src/app/layout/header.component.ts` | Logo, nome do usuário |
| `frontend/src/app/pages/login/login.component.ts` | Título, labels |
| `frontend/src/app/app.routes.ts` | 4 rotas de detalhe adicionadas |
| `backend/.../service/AdminDashboardService.java` | Status calculado, entradas de sessão, CLOB |
| `backend/.../service/OperadorDashboardService.java` | Ownership fix, campos adicionados |
| `backend/.../service/OperacaoService.java` | Map.of mutável |
| `backend/.../service/ChecklistService.java` | Map.of mutável, CLOB no snapshot |
| `backend/.../service/DashboardQueryHelper.java` | CLOB delegado para NativeQueryUtils |
| `backend/.../controller/AdminDashboardController.java` | Mapeamento de campos para relatórios, endpoint entradas-sessao |
| `backend/.../exception/GlobalExceptionHandler.java` | printStackTrace adicionado |
| `backend/.../entity/Checklist.java` | @Enumerated removido |
| `backend/.../entity/ChecklistResposta.java` | @Enumerated removido |
| `backend/.../entity/ChecklistItemTipo.java` | @Enumerated removido |
| `backend/.../entity/RegistroOperacaoOperador.java` | @Enumerated removido |
| `CLAUDE.md` | Reescrito (604 → 192 linhas), regra DRY adicionada |

---

## 16. Modo Somente Leitura nos Formulários do Operador

Os formulários de checklist e operação abriam direto em modo edição. No sistema original, abriam em **somente leitura** com botão "Editar" no rodapé.

**Fluxo implementado (checklist + operação):**
1. Abre em somente leitura (campos readonly, sem interação)
2. Botão "Editar" no rodapé → entra em modo edição
3. "Salvar Alterações" → salva via PUT → recarrega dados → volta para somente leitura
4. "Fechar Aba" sempre disponível

**Implementação técnica:**
- Signal `readOnly` (true por padrão em editMode)
- Checklist: itens exibidos com status Ok/Falha e descrição como texto
- Operação: campos com `[readonly]` e estilo `.field-ro`, comissão como `<div class="field-value">`

## 17. Detalhe da Anormalidade — Operador (Somente Leitura)

- Reutilizado o componente `anormalidade-detalhe.component.ts` (DRY)
- Detecta rota admin vs operador via URL path → usa endpoint correto (`/api/admin/...` ou `/api/operador/...`)
- Seção "Observações Administrativas" (supervisor/chefe) visível somente para admin
- Nova rota `/anormalidade/detalhe` no operador
- Link "SIM" na tabela de operações → abre detalhe somente leitura
- Campo `anormalidade_id` adicionado à query `listMinhasOperacoes` (LEFT JOIN OPR_ANORMALIDADE)

## 18. Formulário ROA — Reformulação Completa

O formulário de Registro de Operação de Áudio foi completamente reescrito para replicar o comportamento do sistema original.

### Estado inicial
- Todos os campos **visíveis** mas **desabilitados** (`formDisabled()`) até selecionar uma sala
- Subtítulo: "Preencha os dados da operação de áudio realizada no local selecionado."

### Layout (igual ao original)
- Local (full width)
- Atividade Legislativa (full width, placeholder "Selecione o tipo")
- Descrição do Evento (full width, placeholder com exemplo)
- Responsável pelo Evento (full width, placeholder com exemplo)
- Data + Horário da Pauta + Início do evento + Término do evento (grid 4 colunas)
- Evento Encerrado + Início da operação + Término da operação (grid 3 colunas)
- Trilha do Gravador 01 + 02 (grid 2 colunas)
- Observações (full width)
- Houve anormalidade? + mensagem explicativa sobre redirecionamento

### Regra de Atividade Legislativa (comissão)
- Auditório → oculta
- Plenário (sem número) → oculta
- Plenário com número (ex: "Plenário 15") → mostra
- Outras salas → mostra
- Travada com valor da sessão quando sessão aberta (`comissaoTravada`)

### 4 estados da sessão
| Estado | Condição | Comportamento |
|--------|----------|---------------|
| `sem_sessao` | Sem sessão aberta, operador sem entradas | 1º operador, formulário vazio, hora_entrada espelha hora_inicio |
| `sem_entrada` | Sessão aberta, operador sem entrada | Pré-preenche dados da última entrada da sessão |
| `uma_entrada` | Operador com 1 entrada | Botão "Editar 1º Registro" + "Novo registro (2ª entrada)" |
| `duas_entradas` | Operador com 2 entradas | Tudo desabilitado, só botões "Editar 1º/2º Registro" |

### Sincronização de horários
- `hora_entrada`: readonly e espelha `hora_inicio` quando 1º operador (sem sessão aberta)
- `hora_saida`: readonly e espelha `hora_fim` quando evento encerrado
- `hora_fim`: desabilitado quando evento não encerrado

### Edição de entradas (1ª/2ª)
- Botões "Editar 1º Registro" / "Editar 2º Registro" visíveis conforme estado
- Carrega dados da entrada, permite editar, salva via PUT `/api/operacao/audio/editar-entrada`
- "Cancelar" volta ao estado anterior recarregando `estado-sessao`

### Cálculo de tipo_evento
- Auditório → `outros`
- Plenário (sem número) → `operacao`
- Comissão com "cessão de sala" → `cessao`
- Demais → `operacao`

### Redirecionamento para anormalidade
- Se `houve_anormalidade=sim` e `tipo_evento` é `operacao` ou `outros` → redireciona para formulário de anormalidade
- Se `tipo_evento=cessao` → **nunca** redireciona (mesmo com anormalidade)

### Bug corrigido: `readOnly()` no modo novo
- `readOnly` era signal inicializado como `true` e aplicado em todos os campos, inclusive no modo novo
- Criado método `isRO()` que retorna `true` apenas quando `editMode() && readOnly()`

## Arquivos Criados (adicionais)

| Arquivo | Descrição |
|---|---|
| `backend/.../enums/TurnoConverter.java` | AttributeConverter para enum Turno |
| `backend/.../enums/StatusRespostaConverter.java` | AttributeConverter para enum StatusResposta |
| `backend/.../enums/TipoWidgetConverter.java` | AttributeConverter para enum TipoWidget |
| `backend/.../enums/TipoEventoConverter.java` | AttributeConverter para enum TipoEvento |

## Arquivos Modificados (adicionais)

| Arquivo | Tipo de Alteração |
|---|---|
| `frontend/src/app/pages/home/checklist-wizard.component.ts` | Modo somente leitura → edição → somente leitura |
| `frontend/src/app/pages/home/operacao-form.component.ts` | Reformulação completa (layout, estados, sincronização, edição de entradas) |
| `frontend/src/app/pages/home/home.component.ts` | Link anormalidade corrigido para `/anormalidade/detalhe` |
| `frontend/src/app/pages/admin/anormalidade-detalhe.component.ts` | Suporte dual admin/operador, observações condicionais |
| `frontend/src/app/app.routes.ts` | Rota `/anormalidade/detalhe` para operador |
| `backend/.../service/OperadorDashboardService.java` | Ownership fix (List Object vs Object[]), campos adicionados (sala_id, comissao_id, ordem, anormalidade_id) |
| `backend/.../service/OperacaoService.java` | 5 retornos Map.of → LinkedHashMap (mutável) |
| `backend/.../service/ChecklistService.java` | Map.of mutável + CLOB fix no snapshot |
| `backend/.../entity/Checklist.java, ChecklistResposta.java, ChecklistItemTipo.java, RegistroOperacaoOperador.java` | @Enumerated removido (substituído por AttributeConverter) |
| `backend/.../exception/GlobalExceptionHandler.java` | printStackTrace para debug |

---

## Sessão 19/03/2026

## 19. Formulário ROA — Mensagem ao Salvar

- Ao salvar com sucesso, exibe mensagem de confirmação (como no código antigo) antes de redirecionar para a tela inicial
- Quando `houve_anormalidade=sim`, exibe mensagem informando o redirecionamento para o formulário de anormalidade, depois redireciona automaticamente

## 20. Formulário ROA — Limpeza de Campos ao Alterar "Evento Encerrado"

- Ao mudar a escolha em "Evento Encerrado" (Sim↔Não), os campos `hora_fim` (Término do evento) e `hora_saida` (Término da operação) são limpos automaticamente
- Corrige comportamento onde o campo ficava desabilitado mas mantinha valor preenchido

## 21. Formulário ROA — Regras de Obrigatoriedade Condicionais

Regras implementadas conforme o sistema original:

| Evento Encerrado | Campo | Estado | Obrigatório |
|---|---|---|---|
| Sim | Término do evento | Habilitado | Sim (asterisco) |
| Sim | Término da operação | Desabilitado | Não |
| Não | Término do evento | Desabilitado | Não |
| Não | Término da operação | Habilitado | Sim (asterisco) |

- Quando sessão aberta: campo "Início da operação" sempre habilitado e obrigatório (asterisco)
- Validação no `onSubmit()` verifica campos obrigatórios condicionais antes de enviar

## 22. Formulário ROA — Foco no Primeiro Campo Obrigatório Vazio

- Ao tentar salvar com campos obrigatórios em branco, ao invés de exibir alerta genérico, o formulário foca automaticamente no primeiro campo obrigatório vazio
- Usa `document.querySelector` com seletores de `name` ou `id` para localizar o campo
- Se há múltiplos campos vazios, ao preencher o primeiro e tentar salvar novamente, foca no próximo

## 23. Formulário ROA — Limpeza ao Mudar Sala

- Ao selecionar outra sala, todos os campos do formulário são resetados para os valores padrão
- Exceção: se a sala selecionada tem sessão aberta, carrega os dados do registro anterior (comportamento de pré-preenchimento)

## 24. Aviso de Sessão Aberta — Estilo Discreto

- Removido o banner azul chamativo (`.alert-info`) que exibia "Sessão aberta neste local"
- Substituído por texto discreto acima do título (como no sistema original):
  - "Registro aberto por **Nome do Operador**."
  - "Segundo registro feito por **Nome do Operador**."
- Posicionado antes da `<hr>` e do título "Registro de Operação de Áudio"

## 25. Títulos de Formulário/Páginas — Padronização

- Criada classe global `.page-title` em `styles.scss` com `font-size: 1.5rem`, `font-weight: bold`, `color: #111827`
- Aplicada em todos os formulários e páginas (ROA, checklist, anormalidade, detalhe admin, etc.)
- Princípio DRY: estilo definido uma vez, reutilizado em todos os componentes

## 26. Formulário de Anormalidade — Reformulação Completa

O formulário de Registro de Anormalidade foi completamente refeito para replicar o layout e funcionalidades do sistema original (Python).

### Layout replicado do original
- **1) Dados do Evento**: Data (readonly), Local do evento (readonly, sala da operação), Nome do evento (readonly, pré-preenchido)
- **2) Responsáveis**: Nome do Secretário da Comissão, da Mesa ou do responsável pelo evento (obrigatório)
- **3) Detalhes da Anormalidade**: Horário do início da anormalidade (obrigatório) + Descrição da anormalidade (textarea, obrigatório)
- **Houve suspensão, adiamento ou cancelamento do evento?** (Não/Sim) → se Sim: Duração do adiamento (textarea, obrigatório)
- **Houve reclamação?** (Não/Sim) → se Sim: Conteúdo da reclamação (textarea, obrigatório)
- **Foi necessário acionar a manutenção?** (Não/Sim) → se Sim: Horário do acionamento (obrigatório)
- **A anormalidade foi resolvida pela própria operação?** (Não/Sim) → se Sim: Procedimentos adotados (textarea, obrigatório)
- Subtítulo: "Vinculado ao registro de operação nº X"
- Botões: "Voltar" e "Salvar registro"

### Dados pré-preenchidos
- Data, sala e nome do evento são herdados do registro de operação (`registro_id` e `entrada_id` passados via query params)
- Endpoint `/api/operacao/audio/entrada-info` criado para fornecer esses dados

### Campos condicionais
- Campos de detalhamento só aparecem quando a resposta é "Sim" (ngIf/template condicional)
- Ao mudar de "Sim" para "Não", o campo dependente é limpo automaticamente

## 27. AnormalidadeService — Correção de Parse de Data

- `LocalDate.parse('2026-03-19 00:00:00.0')` falhava pois o valor vinha do Oracle com timestamp
- Corrigido para tratar o formato antes do parse: extrai apenas os primeiros 10 caracteres (`yyyy-MM-dd`) quando o valor contém espaço

## 28. Detalhe da Anormalidade (Operador) — Correção "criado_por_nome"

- O endpoint `getMinhaAnormalidadeDetalhe` não fazia JOIN com `PES_OPERADOR`, então o campo "Registrado por" exibia "Sistema" ao invés do nome do operador
- Adicionado `LEFT JOIN PES_OPERADOR o ON o.ID = a.CRIADO_POR` na query
- Também adicionado `LEFT JOIN OPR_ANORMALIDADE_ADMIN` para que o operador possa ver observações do supervisor/chefe em modo readonly

## 29. Formulário ROA — Indicador "editado" nos Campos

- Campos editados exibem badge sutil "editado" ao lado do label (como no sistema original)
- Estilo: texto cinza, itálico, fonte reduzida (`.badge-field-edited`)
- O label "Término do evento" recebe `font-size` menor quando editado para evitar desalinhamento no grid de 4 colunas
- Labels do grid de 4 colunas têm `min-height` fixa para manter alinhamento vertical

## 30. Formulário ROA — Erro CLOB no Oracle ao Editar Entrada

- `ORA-00932: tipos de dados inconsistentes: esperava - obteve CLOB` ao editar campos como trilha do gravador
- Causa: `NVL(OBSERVACOES, ' ')` não funciona com colunas CLOB no Oracle — CLOB não pode ser comparado diretamente com VARCHAR2
- Correção: campos CLOB (`OBSERVACOES`) usam `NVL(TO_CHAR(OBSERVACOES), ' ')` para converter antes da comparação

## 31. Checklist (Wizard) — Radio Buttons Estilo Clássico

- Radio buttons do wizard de verificação de plenários mudados de "cards" (botões grandes) para estilo clássico com radio buttons visíveis
- Ícones ✅ (Ok) e ✖ vermelho (Falha) ao lado das opções, replicando o sistema original
- Gap entre radio buttons aumentado para melhor legibilidade

## 32. Checklist (Wizard) — Botão "Confirmar e Avançar" no Último Item

- O último item do wizard exibia "Finalizar →" ao invés de "Confirmar e Avançar →"
- Corrigido: todos os itens mostram "Confirmar e Avançar →"; botão "Salvar Verificação" aparece apenas na tela de observações

## 33. Checklist (Wizard) — Correção Map.of() Imutável no Registro

- `ChecklistController.registro()` fazia `result.put("ok", true)` no `Map.of()` imutável retornado por `ChecklistService.registrar()`
- Erro: `UnsupportedOperationException` — registro salvava mas resposta HTTP falhava
- Correção: controller cria `new LinkedHashMap<>(result)` antes de adicionar campos

## 34. Checklist (Edição) — Itens de Texto sem Ícone Ok/Falha

- Itens de tipo `text` (ex: "Trilha do Gravador 01/02") exibiam ícone ✅ Ok no modo readonly
- Corrigido: ícone Ok/Falha só aparece para itens de tipo `radio`
- Campos de texto sem valor exibem "--" (como no sistema original)

## Arquivos Modificados (sessão 19/03/2026)

| Arquivo | Tipo de Alteração |
|---|---|
| `frontend/src/app/pages/home/operacao-form.component.ts` | Mensagem ao salvar, limpeza ao mudar sala, limpeza ao mudar evento encerrado, foco em campo obrigatório, regras de obrigatoriedade condicionais, aviso de sessão discreto, badge "editado" nos campos |
| `frontend/src/app/pages/home/anormalidade-form.component.ts` | Reformulação completa do formulário de anormalidade |
| `frontend/src/app/pages/home/checklist-wizard.component.ts` | Radio buttons clássicos, botão "Confirmar e Avançar", itens texto sem ícone, "--" para valores vazios |
| `frontend/src/styles.scss` | Classe `.page-title` e `.badge-field-edited` adicionadas |
| `backend/.../service/AnormalidadeService.java` | Correção parse de data Oracle (timestamp → LocalDate) |
| `backend/.../service/OperacaoService.java` | Correção CLOB no NVL (TO_CHAR para OBSERVACOES) |
| `backend/.../service/OperadorDashboardService.java` | JOIN com PES_OPERADOR e OPR_ANORMALIDADE_ADMIN no detalhe da anormalidade |
| `backend/.../controller/AnormalidadeController.java` | Endpoint `/entrada-info` para dados pré-preenchidos |
| `backend/.../controller/ChecklistController.java` | LinkedHashMap para evitar Map.of() imutável |

---

## Sessão 02/04/2026

## 35. Tabela "Verificação de Salas" — Colunas Qtde. OK e Qtde. Falha

- As colunas "Qtde. OK" e "Qtde. Falha" na tabela de checklists da home do operador exibiam sempre **0**
- Causa: a query `listMeusChecklists` em `OperadorDashboardService` não incluía subqueries para contar respostas
- Adicionadas subqueries `SELECT COUNT(*)` da tabela `FRM_CHECKLIST_RESPOSTA` com JOIN em `FRM_CHECKLIST_ITEM_TIPO` (excluindo itens de tipo `text`), agrupadas por `STATUS = 'Ok'` e `STATUS = 'Falha'`

## 36. Ordem dos Itens no Formulário de Edição do Checklist

- Ao editar um checklist existente (botão "Formulário"), os itens de verificação apareciam em ordem diferente do formulário novo
- Causa: a query de detalhe ordenava por `t.ID` (ID do item tipo) ao invés de `FRM_CHECKLIST_SALA_CONFIG.ORDEM`
- Corrigido em dois endpoints: detalhe do operador (`OperadorDashboardService`) e detalhe do admin (`AdminDashboardService`)
- Adicionado `LEFT JOIN FRM_CHECKLIST_SALA_CONFIG sc` com `ORDER BY sc.ORDEM ASC, t.ID ASC`

## 37. Ícones nos Radio Buttons do Modo Edição do Checklist

- No modo edição, os botões "Ok" e "Falha" não tinham os ícones ✅ e ✖ como no sistema original
- Adicionados os ícones no template, com o ✖ estilizado em vermelho via `style="color:var(--color-red)"`

## 38. Formatação de Data no Modo Somente Leitura do Checklist

- O campo "Data" no modo readonly exibia `2026-04-02` (formato ISO) ao invés de `02/04/2026`
- Aplicado o pipe `fmtDate` existente (`{{ dataOperacao | fmtDate }}`)
- Adicionado import do `FmtDatePipe` no componente
- Verificação: todos os outros componentes já usavam o pipe corretamente; este era o único lugar faltando

## 39. Badge "editado" nas Observações do Checklist

- O campo "Observações" não exibia a label "editado" após salvar, diferente dos itens de verificação
- Implementação inicial (comparação em tempo real `observacoes !== observacoesOriginal`) causava comportamento invertido: aparecia durante edição e sumia após salvar
- Corrigido: badge agora usa o campo `observacoes_editado` retornado pelo backend (flag `OBSERVACOES_EDITADO` no banco, setada por `ChecklistService.editar()`)
- Adicionado `c.OBSERVACOES_EDITADO` na query de detalhe do operador

## 40. Persistência de Rascunho no Checklist (localStorage)

Funcionalidade nova replicada do sistema original (Python/JS).

- **Chave:** `checklist_draft_<userId>` (por operador, para evitar conflitos em computadores compartilhados)
- **Salva** a cada avanço do wizard (`startWizard`, `nextStep`) com: `salaId`, `salaNome`, `itens`, `currentIndex`, `respostas`, `startTime`, `step`, `savedAt`
- **Restaura** ao abrir o formulário no modo novo (`ngOnInit`) — retoma na etapa exata (wizard ou finish)
- **Expiração:** descarta se for de dia diferente ou se tiver mais de 2 horas (7.200.000 ms)
- **Limpa** ao salvar com sucesso
- **Erros de localStorage** ignorados silenciosamente (apenas `console.warn`)
- `AuthService` injetado para obter o `user().id` na chave do draft

## 41. Proteção contra Envio Duplicado no Checklist

Funcionalidade nova replicada do sistema original (Python).

### Frontend
- Guard `if (this.saving()) return` no início do `submit()` — impede duplo clique
- No **sucesso**: mantém botão desabilitado durante o redirecionamento (não reseta `saving`)
- No **erro**: reabilita o botão e exibe a mensagem do backend (`err.error?.error`)

### Backend
- Query `SELECT CASE WHEN EXISTS (...)` verifica se o mesmo operador (`CRIADO_POR`) já enviou checklist para a mesma sala (`SALA_ID`) nos últimos 5 minutos (`CRIADO_EM >= SYSTIMESTAMP - INTERVAL '5' MINUTE`)
- Posicionada após validações de campos/itens e antes da inserção
- Rejeita com HTTP 400: "Já existe uma verificação sua para este local enviada há menos de 5 minutos. Aguarde antes de enviar novamente."

## 42. Tabela "Registros de Operação de Áudio" — Colunas e Campos Corrigidos

- Headers renomeados: "Início" → "Início Operação", "Fim" → "Fim Operação"
- Backend: query `listMinhasOperacoes` buscava `HORARIO_INICIO`/`HORARIO_TERMINO` (horários do evento) — corrigido para `HORA_ENTRADA`/`HORA_SAIDA` (horários da operação)
- Frontend: campos ajustados de `op['horario_inicio']`/`op['horario_termino']` para `op['hora_entrada']`/`op['hora_saida']`

## 43. Normalização de Horários — Formato hh:mm:ss

- Ao editar uma entrada de operação, os campos de horário eram salvos como `"hh:mm"` (formato do `<input type="time">`) ao invés de `"hh:mm:ss"` (formato do banco)
- Isso causava falsos positivos na detecção de edição: `"14:30:00" != "14:30"` → campo marcado como "editado" indevidamente
- Criado método `normalizeTime()` em `OperacaoService` que converte `"hh:mm"` → `"hh:mm:ss"`
- Aplicado em todos os campos de horário tanto na criação (`registrarEntrada`) quanto na edição (`editarEntrada`): `horarioPauta`, `horaInicio`, `horaFim`, `horaEntrada`, `horaSaida`
- Registro ID 64 corrigido manualmente no banco (horários restaurados para `"hh:mm:ss"`, flag `HORARIO_PAUTA_EDITADO` resetada)

## 44. Layout — Botão "Gerar Relatório" e Paginação nas Tabelas do Operador

- Nas tabelas "Verificação de Salas" e "Registros de Operação de Áudio" da home do operador
- Botão "Gerar Relatório" movido do header (acima da tabela) para abaixo da tabela, à esquerda
- Paginação posicionada à direita na mesma linha
- Criado container `.table-footer` com `display: flex; justify-content: space-between`

## Arquivos Modificados (sessão 02/04/2026)

| Arquivo | Tipo de Alteração |
|---|---|
| `backend/.../service/OperadorDashboardService.java` | Subqueries qtde_ok/qtde_falha, ORDER BY com SALA_CONFIG.ORDEM, campo OBSERVACOES_EDITADO no detalhe |
| `backend/.../service/AdminDashboardService.java` | ORDER BY com SALA_CONFIG.ORDEM no detalhe do checklist |
| `backend/.../service/ChecklistService.java` | Verificação de duplicata (janela 5 min) antes de inserir |
| `backend/.../service/OperacaoService.java` | Método `normalizeTime()`, normalização de horários na criação e edição de entradas |
| `frontend/src/app/pages/home/checklist-wizard.component.ts` | Ícones ✅/✖ no modo edição, pipe fmtDate, rascunho localStorage por usuário, proteção duplo clique, mensagens de erro do backend |
| `frontend/src/app/pages/home/home.component.ts` | Headers "Início/Fim Operação", campos hora_entrada/hora_saida, layout botão relatório + paginação |

## 45. Foto do Operador no Header

- A foto do operador não era exibida no header ao lado do nome e botão "Sair"
- **3 problemas encontrados e corrigidos:**
  1. **`AuthController.java`**: o endpoint `/api/login` não retornava `foto_url` na resposta (apenas `/api/whoami` retornava) — adicionado `authService.getFotoUrl()` na resposta do login
  2. **`header.component.ts`**: não havia código para exibir a foto — adicionado `<img>` circular (32x32px) condicional ao `foto_url`, com URL montada usando `apiBaseUrl` para resolver o caminho relativo (`/files/...`) do backend (porta 8000) vs frontend (porta 4200)
  3. **`SecurityConfig.java` + `JwtAuthenticationFilter.java`**: o endpoint `/files/**` não estava liberado — o filtro JWT retornava 401 para requests sem token. Adicionado `permitAll()` no SecurityConfig e `shouldNotFilter` no filtro JWT

## 46. Responsividade Mobile — Formulários dos Operadores

- Formulários (ROA, anormalidade, checklist) usavam grids de colunas fixas (2, 3, 4 cols) que não se adaptavam a telas pequenas
- Adicionadas media queries `@media (max-width: 600px)` para colapsar todos os grids em 1 coluna:
  - **`styles.scss`** (global): `.grid-2`, `.grid-3`, `.grid-4` → `grid-template-columns: 1fr`; `.card-custom` com padding reduzido
  - **`operacao-form.component.ts`**: `.form-grid-2/3/4` colapsam; botões de ação empilham verticalmente
  - **`anormalidade-form.component.ts`**: `.form-grid-3`, `.form-grid-2-toggle` colapsam
  - **`checklist-wizard.component.ts`**: grid de setup (Data/Local) extraído de style inline para classe `.setup-grid` com media query

## 47. Inputs com Fundo Cinza no Safari/iOS

- No Safari (iPhone), inputs de `type="date"` e `type="time"` ficavam com fundo cinza (estilo nativo do iOS)
- Adicionado `background-color: #fff` e `min-height: 44px` no seletor global `input, select, textarea`
- `-webkit-appearance: none` aplicado apenas nos inputs de `date` e `time` para remover o estilo nativo sem afetar outros inputs
- `min-height: 44px` garante tamanho adequado para touch targets (recomendação Apple HIG)

## Arquivos Modificados (adicionais — sessão 02/04/2026)

| Arquivo | Tipo de Alteração |
|---|---|
| `backend/.../controller/AuthController.java` | `foto_url` adicionado à resposta do login |
| `backend/.../config/SecurityConfig.java` | `/files/**` liberado com `permitAll()` |
| `backend/.../security/JwtAuthenticationFilter.java` | `/files/` adicionado ao `shouldNotFilter` |
| `frontend/src/app/layout/header.component.ts` | Foto do operador (img circular) ao lado do nome |
| `frontend/src/app/pages/home/operacao-form.component.ts` | Media queries para responsividade mobile |
| `frontend/src/app/pages/home/anormalidade-form.component.ts` | Media queries para responsividade mobile |
| `frontend/src/app/pages/home/checklist-wizard.component.ts` | Classe `.setup-grid` com media query |
| `frontend/src/styles.scss` | Grids responsivos globais, inputs com fundo branco e min-height |

---

## Sessão 04/04/2026

## 48. Radio Buttons Gigantes

- Os radio buttons (`input[type="radio"]`) ficaram muito grandes após o ajuste de `min-height: 44px` para mobile (correção #47)
- Causa: o seletor global `input, select, textarea` em `styles.scss` aplicava `min-height: 44px` e `padding: 10px 12px` a todos os inputs, incluindo radio e checkbox
- Corrigido: seletor alterado para `input:not([type="radio"]):not([type="checkbox"]), select, textarea`

## 49. Coluna "Checklist?" — Semântica Corrigida

- Na tabela "Operações de Áudio" dos administradores, a coluna "Checklist?" exibia "Não Realizado" mesmo quando o checklist havia sido preenchido
- Causa: a query usava `r.CHECKLIST_DO_DIA_OK` (flag booleana que indica se todos os itens passaram sem falha), mas o significado correto de "Realizado" é "o checklist foi preenchido", independente de ter falhas
- No sistema Python original: `CASE WHEN r.checklist_do_dia_id IS NOT NULL THEN 'Realizado'`
- Corrigido em `AdminDashboardService.listOperacoes()`: substituído `r.CHECKLIST_DO_DIA_OK` por `CASE WHEN r.CHECKLIST_DO_DIA_ID IS NOT NULL THEN 1 ELSE 0 END`

## 50. Inversão de Campos — "Evento Encerrado" e "Término do Evento"

- No formulário de Registro de Operação de Áudio, os campos "Término do evento" e "Evento Encerrado" foram invertidos de posição conforme solicitação
- Alterados em 2 componentes: `operacao-form.component.ts` (formulário operador + edição admin) e `operacao-detalhe.component.ts` (visualização admin)

## 51. Coluna "Duração" — Formato hh:mm:ss

- Na tabela "Verificação de Plenários" dos administradores, a coluna "Duração" exibia no formato `Xh Ymin` (ex: `15min`)
- No sistema original exibia `h:mm:ss` (ex: `0:15:32`)
- Corrigido em `AdminDashboardComponent.calcDuracao()`: cálculo alterado de minutos para segundos, formato de saída mudado para `h:mm:ss`

## 52. Itens de Texto — Status "Texto" na Tabela Expandida de Checklists

- Ao expandir uma linha na tabela de Verificação de Plenários, itens do tipo texto (ex: "Trilha do Gravador 01") exibiam status "Ok" em verde
- No sistema original, itens de texto exibem "Texto" com cor preta (sem badge de cor)
- Corrigido em duas frentes:
  - **Backend** (`AdminDashboardService.getChecklistDetalhe`): adicionado `t.TIPO_WIDGET` na query e `tipo_widget` no mapeamento dos itens
  - **Frontend** (`admin-dashboard.component.ts`): template condicional — se `tipo_widget === 'text'`, exibe "Texto" sem classe de cor

## 53. Itens de Texto — Sem Ícone no Formulário de Detalhe

- Na página de detalhe do checklist (botão "Formulário"), itens do tipo texto exibiam ícone "✅ Ok"
- Corrigido em `checklist-detalhe.component.ts`: ícone/status exibido apenas quando `tipo_widget !== 'text'`

## 54. Página "Edição de Formulários" — Reformulação Completa

Componente `admin-form-edit.component.ts` reescrito para replicar o comportamento do sistema original (`form_edit.html` + `form_edit.js`):

- **Drag-and-drop** para reordenação de itens ativos (coluna "⋮⋮")
- **Separação visual** entre itens ativos (topo, com posição numerada) e inativos (final, sem número, em cinza)
- **Reorganização automática** ao ativar/desativar item
- **Edição por duplo-clique** nos nomes (substituindo inputs sempre visíveis)
- **Linha "Novo registro..."** posicionada entre ativos e inativos
- **Destaque azul** em itens modificados/adicionados (`_highlight`)
- **Blur no input de novo item** confirma a entrada (além do Enter)
- **Tracking por referência** (`track item`) em vez de `track $index` para evitar bug de checkbox ao ativar item inativo
- **Mensagem informativa** no menu "Itens de Verificação" ao selecionar uma sala
- **Seletor de sala** filtra apenas salas ativas
- **Card "Edição de Locais" desabilitado** — texto "Gerenciado pelo sistema", com opacidade reduzida e cursor not-allowed

## 55. Validação de Formato — Edição de Comissões

- Ao adicionar ou editar comissão, o nome deve seguir o formato "SIGLA - Nome completo" (ex: "CAE - Comissão de Assuntos Econômicos")
- Validação: sigla >= 2 caracteres, separador " - " obrigatório, nome completo >= 5 caracteres
- Alert exibido com exemplo e regras caso o formato não seja respeitado

## 56. Botão "Aplicar a Todos os Locais" — Ajustes

- Agora inclui a sala de origem na aplicação (antes era excluída)
- Aplica somente nos plenários numerados (padrão "Plenário XX") — exclui "Plenário" (sem número) e "Auditório Petrônio Portella"
- Botão oculto quando a sala selecionada não é um plenário numerado
- Botão "Salvar" exibe o nome da sala selecionada (ex: "Salvar — Plenário 02")

## 57. Coluna "Alterar Senha" — Tabela de Operadores

- Nova coluna "Alterar Senha" na tabela "Operadores de Áudio" do painel administrativo
- Cada linha contém botão "Alterar" que abre popup centralizado (modal) com overlay escuro
- Campos: "Nova Senha" e "Confirmar Senha" (type password)
- Botões: "Alterar" e "Cancelar"
- Validações: senha mínima 4 caracteres, confirmação deve coincidir
- **Backend**: novo endpoint `POST /api/admin/operador/{id}/alterar-senha` — atualiza hash BCrypt
- **Frontend**: `ChangeDetectorRef.detectChanges()` necessário para atualizar a view após resposta do HTTP (callback executava fora da zona do Angular)

## Arquivos Modificados (sessão 04/04/2026)

| Arquivo | Tipo de Alteração |
|---|---|
| `frontend/src/styles.scss` | Seletor de input exclui radio/checkbox do min-height |
| `frontend/src/app/pages/admin/admin-dashboard.component.ts` | Duração h:mm:ss, tipo_widget no accordion, coluna Alterar Senha com popup, ChangeDetectorRef |
| `frontend/src/app/pages/admin/admin-form-edit.component.ts` | Reformulação completa: drag-drop, destaque, validação comissões, botão aplicar condicional |
| `frontend/src/app/pages/admin/checklist-detalhe.component.ts` | Ícone condicional para itens de texto |
| `frontend/src/app/pages/admin/operacao-detalhe.component.ts` | Inversão campos Evento Encerrado / Término do evento |
| `frontend/src/app/pages/home/operacao-form.component.ts` | Inversão campos Evento Encerrado / Término do evento |
| `backend/.../service/AdminDashboardService.java` | Query checklist_do_dia_id IS NOT NULL, tipo_widget no detalhe |
| `backend/.../service/AdminCrudService.java` | Método `changeOperadorPassword()`, filtro plenários numerados no aplicar-todas |
| `backend/.../controller/AdminCrudController.java` | Endpoint `POST /operador/{id}/alterar-senha` |
