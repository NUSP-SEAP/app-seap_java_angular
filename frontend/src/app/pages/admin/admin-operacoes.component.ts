import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService, ListParams } from '../../core/services/api.service';
import { PaginationMeta } from '../../core/models/user.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef, ColumnFilterState } from '../../shared/components/column-filter.component';
import { getDistinct, buildFilters, buildReportParams, mesNome } from '../../core/helpers/table.helpers';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FmtTimePipe } from '../../shared/pipes/fmt-time.pipe';

interface TableState extends ListParams { page:number; limit:number; sort:string; direction:string; search:string; }

@Component({
  selector: 'app-admin-operacoes',
  standalone: true,
  imports: [RouterLink, FormsModule, PaginationComponent, ColumnFilterComponent, FmtDatePipe, FmtTimePipe],
  template: `
    <h1>Operações de Áudio</h1>
    <a routerLink="/admin" class="back-link">&larr; Voltar ao Painel</a>

    <!-- ════════════ REGISTROS DE OPERAÇÃO ════════════ -->
    <section>
      <div class="section-header">
        <h2>Registros de Operação</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="opSearch" (input)="onOpSearch()" placeholder="Buscar por local, operador, data, ..." class="search-input search-wide">
        </div>
      </div>

      <!-- Controles: Agrupar + RDS -->
      <div class="controls-row">
        <div class="controls-left">
          <label class="check-label">
            <input type="checkbox" [(ngModel)]="groupBySala" (change)="onGroupChange()">
            Agrupar por local
          </label>
        </div>
        <div class="controls-right">
          <select [(ngModel)]="rdsAno" (change)="onAnoChange()" class="ctrl-select">
            <option value="">Ano</option>
            @for (a of rdsAnos(); track a) { <option [value]="a">{{ a }}</option> }
          </select>
          <select [(ngModel)]="rdsMes" [disabled]="!rdsAno" class="ctrl-select">
            <option value="">Mês</option>
            @for (m of rdsMeses(); track m) { <option [value]="m">{{ mesNome(m) }}</option> }
          </select>
          <button class="btn-rds" [disabled]="!rdsAno || !rdsMes" (click)="gerarRds()">Gerar RDS</button>
        </div>
      </div>

      <!-- MODO AGRUPADO -->
      @if (groupBySala) {
        <div class="table-container">
          <table class="data-table">
            <thead><tr>
              <th style="width:30px"></th>
              <th><app-column-filter [col]="sessCols[0]" [distinctValues]="gd(opMeta(),'sala')" [currentSort]="opState.sort" [currentDir]="opState.direction" (sortChange)="onOpSortEvt($event)" (filterChange)="onOpFilterEvt($event)" /></th>
              <th><app-column-filter [col]="sessCols[1]" [distinctValues]="gd(opMeta(),'data')" [currentSort]="opState.sort" [currentDir]="opState.direction" (sortChange)="onOpSortEvt($event)" (filterChange)="onOpFilterEvt($event)" /></th>
              <th>Evento</th>
              <th>Pauta</th>
              <th>Início</th>
              <th>Fim</th>
              <th>Verificação</th>
            </tr></thead>
            <tbody>
              @if (opRows().length === 0) {
                <tr><td colspan="8" class="empty-state">{{ opLoading() ? 'Carregando...' : 'Nenhuma sessão.' }}</td></tr>
              } @else {
                @for (s of opRows(); track s['id']) {
                  <tr class="row-clickable" (click)="toggleSessao(s)">
                    <td><span class="btn-toggle">{{ s['_exp'] ? '▼' : '▶' }}</span></td>
                    <td><strong>{{ s['sala_nome'] }}</strong></td>
                    <td>{{ s['data'] | fmtDate }}</td>
                    <td [title]="formatEvento(s)">{{ truncate(formatEvento(s), 30) }}</td>
                    <td>{{ s['ultimo_pauta'] | fmtTime }}</td>
                    <td>{{ s['ultimo_inicio'] | fmtTime }}</td>
                    <td>{{ s['ultimo_termino'] | fmtTime }}</td>
                    <td [style.color]="s['checklist_do_dia_ok'] ? 'var(--color-green)' : 'var(--muted)'">{{ s['checklist_do_dia_ok'] ? 'Realizado' : 'Não Realizado' }}</td>
                  </tr>
                  @if (s['_exp']) {
                    <tr class="accordion-row">
                      <td colspan="8">
                        @if (!s['_entradas']) {
                          <p class="text-muted-sm">Carregando entradas...</p>
                        } @else if (asArr(s['_entradas']).length === 0) {
                          <p class="text-muted-sm">Nenhuma entrada registrada nesta sessão.</p>
                        } @else if (s['_is_plenario_principal']) {
                          <!-- Subtabela: Plenário Principal -->
                          @for (e of asArr(s['_entradas']); track e['id']||$index) {
                            <table class="sub-table">
                              <thead><tr><th>Operador</th><th>Anom?</th></tr></thead>
                              <tbody>
                                @if (asArr(e['operadores']).length > 0) {
                                  @for (op of asArr(e['operadores']); track $index) {
                                    <tr class="row-clickable" (dblclick)="openEntrada(e)">
                                      <td>{{ op }}</td>
                                      @if ($first) {
                                        <td [attr.rowspan]="asArr(e['operadores']).length" [class]="e['anormalidade'] ? 'badge-falha' : 'badge-ok'" style="vertical-align:middle">{{ e['anormalidade'] ? 'SIM' : 'Não' }}</td>
                                      }
                                    </tr>
                                  }
                                } @else {
                                  <tr class="row-clickable" (dblclick)="openEntrada(e)">
                                    <td>{{ e['preenchido_por'] }}</td>
                                    <td [class]="e['anormalidade'] ? 'badge-falha' : 'badge-ok'">{{ e['anormalidade'] ? 'SIM' : 'Não' }}</td>
                                  </tr>
                                }
                              </tbody>
                            </table>
                          }
                        } @else {
                          <!-- Subtabela: Plenários numerados -->
                          <table class="sub-table">
                            <thead><tr><th>Nº</th><th>Operador</th><th>Início Operação</th><th>Fim Operação</th><th>Observações</th><th>Anom?</th></tr></thead>
                            <tbody>
                              @for (e of asArr(s['_entradas']); track e['id']||$index) {
                                <tr class="row-clickable" (dblclick)="openEntrada(e)">
                                  <td>{{ e['ordem'] }}º</td>
                                  <td>{{ e['operador'] }}</td>
                                  <td>{{ e['hora_entrada'] | fmtTime }}</td>
                                  <td>{{ e['hora_saida'] | fmtTime }}</td>
                                  <td [title]="e['observacoes']">{{ truncate(e['observacoes'], 20) }}</td>
                                  <td [class]="e['anormalidade'] ? 'badge-falha' : 'badge-ok'">{{ e['anormalidade'] ? 'SIM' : 'Não' }}</td>
                                </tr>
                              }
                            </tbody>
                          </table>
                        }
                      </td>
                    </tr>
                  }
                }
              }
            </tbody>
          </table>
        </div>
      } @else {
        <!-- MODO LISTA PLANA -->
        <div class="table-container">
          <table class="data-table">
            <thead><tr>
              <th><app-column-filter [col]="entCols[0]" [distinctValues]="gd(opMeta(),'sala')" [currentSort]="opState.sort" [currentDir]="opState.direction" (sortChange)="onOpSortEvt($event)" (filterChange)="onOpFilterEvt($event)" /></th>
              <th><app-column-filter [col]="entCols[1]" [distinctValues]="gd(opMeta(),'data')" [currentSort]="opState.sort" [currentDir]="opState.direction" (sortChange)="onOpSortEvt($event)" (filterChange)="onOpFilterEvt($event)" /></th>
              <th>Operador</th><th>Tipo</th><th>Evento</th><th>Pauta</th><th>Início</th><th>Fim</th><th>Anom?</th>
            </tr></thead>
            <tbody>
              @if (opRows().length === 0) {
                <tr><td colspan="9" class="empty-state">{{ opLoading() ? 'Carregando...' : 'Nenhuma entrada.' }}</td></tr>
              } @else {
                @for (e of opRows(); track e['id']) {
                  <tr class="row-clickable" (dblclick)="openEntrada(e)">
                    <td><strong>{{ e['sala_nome'] }}</strong></td>
                    <td>{{ e['data'] | fmtDate }}</td>
                    <td>{{ e['operador_nome'] }}</td>
                    <td>{{ e['tipo_evento'] }}</td>
                    <td>{{ e['nome_evento'] }}</td>
                    <td>{{ e['horario_pauta'] | fmtTime }}</td>
                    <td>{{ e['horario_inicio'] | fmtTime }}</td>
                    <td>{{ e['horario_termino'] | fmtTime }}</td>
                    <td [class]="e['houve_anormalidade'] ? 'badge-falha' : 'badge-ok'">{{ e['houve_anormalidade'] ? 'SIM' : 'Não' }}</td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      }

      <!-- Paginação + Relatório -->
      <div class="pag-report-row">
        <div class="report-controls">
          <select [(ngModel)]="reportFormat" class="ctrl-select">
            <option value="">Selecione a extensão...</option>
            <option value="pdf">PDF</option>
            <option value="docx">DOCX</option>
          </select>
          <button class="btn-report" [disabled]="!reportFormat" (click)="gerarRelatorioOp()">Gerar Relatório</button>
        </div>
        <app-pagination [meta]="opMeta()!" (pageChange)="opState.page=$event; loadOperacoes()" (limitChange)="opState.limit=$event; opState.page=1; loadOperacoes()" />
      </div>
    </section>

    <!-- ════════════ RELATÓRIOS DE ANORMALIDADES ════════════ -->
    <section>
      <div class="section-header">
        <h2>Relatórios de Anormalidades</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="anomSearch" (input)="onAnomSearch()" placeholder="Buscar por data, local, ..." class="search-input search-wide">
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th><app-column-filter [col]="anomCols[0]" [distinctValues]="gd(anomMeta(),'data')" [currentSort]="anomState.sort" [currentDir]="anomState.direction" (sortChange)="onAnomSortEvt($event)" (filterChange)="onAnomFilterEvt($event)" /></th>
            <th><app-column-filter [col]="anomCols[1]" [distinctValues]="gd(anomMeta(),'sala')" [currentSort]="anomState.sort" [currentDir]="anomState.direction" (sortChange)="onAnomSortEvt($event)" (filterChange)="onAnomFilterEvt($event)" /></th>
            <th>Registrado por</th>
            <th>Descrição</th>
            <th>Solucionada</th>
            <th>Prejuízo</th>
            <th>Reclamação</th>
            <th>Ação</th>
          </tr></thead>
          <tbody>
            @if (anomRows().length === 0) {
              <tr><td colspan="8" class="empty-state">{{ anomLoading() ? 'Carregando...' : 'Nenhuma anormalidade.' }}</td></tr>
            } @else {
              @for (a of anomRows(); track a['id']) {
                <tr>
                  <td>{{ a['data'] | fmtDate }}</td>
                  <td>{{ a['sala_nome'] }}</td>
                  <td>{{ a['registrado_por'] }}</td>
                  <td [title]="a['descricao_anormalidade']">{{ truncate(a['descricao_anormalidade'], 50) }}</td>
                  <td [class]="a['resolvida_pelo_operador'] ? 'badge-ok' : 'badge-falha'" style="font-weight:700">{{ a['resolvida_pelo_operador'] ? 'Sim' : 'Não' }}</td>
                  <td [class]="a['houve_prejuizo'] ? 'badge-falha' : ''" [style.font-weight]="a['houve_prejuizo'] ? '700' : '400'">{{ a['houve_prejuizo'] ? 'Sim' : 'Não' }}</td>
                  <td [class]="a['houve_reclamacao'] ? 'badge-falha' : ''" [style.font-weight]="a['houve_reclamacao'] ? '700' : '400'">{{ a['houve_reclamacao'] ? 'Sim' : 'Não' }}</td>
                  <td><button class="btn-xs" (click)="openAnormalidade(a)">Detalhes</button></td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <div class="pag-report-row">
        <div class="report-controls">
          <select [(ngModel)]="anomReportFormat" class="ctrl-select">
            <option value="">Selecione a extensão...</option>
            <option value="pdf">PDF</option>
            <option value="docx">DOCX</option>
          </select>
          <button class="btn-report" [disabled]="!anomReportFormat" (click)="gerarRelatorioAnom()">Gerar Relatório</button>
        </div>
        <app-pagination [meta]="anomMeta()!" (pageChange)="anomState.page=$event; loadAnormalidades()" (limitChange)="anomState.limit=$event; anomState.page=1; loadAnormalidades()" />
      </div>
    </section>
  `,
  styles: [`
    section { margin-bottom:32px; }
    .controls-row { display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; flex-wrap:wrap; gap:8px; }
    .controls-left { display:flex; align-items:center; gap:12px; }
    .controls-right { display:flex; align-items:center; gap:6px; }
    .check-label { display:flex; align-items:center; gap:6px; font-size:.85rem; cursor:pointer; input{cursor:pointer;} }
    .btn-rds { background:#16a34a; color:#fff; border:none; border-radius:6px; padding:5px 14px; font-size:.8rem; font-weight:600; cursor:pointer; &:hover{background:#15803d;} &:disabled{opacity:.5;cursor:not-allowed;} }
  `],
})
export class AdminOperacoesComponent implements OnInit {
  private api = inject(ApiService);
  private dOp: any; private dAnom: any;

  // ── Column definitions ──
  sessCols: ColumnFilterDef[] = [
    { key: 'sala', label: 'Local', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
  ];
  entCols: ColumnFilterDef[] = [
    { key: 'sala', label: 'Local', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
  ];
  anomCols: ColumnFilterDef[] = [
    { key: 'data', label: 'Data', type: 'date' },
    { key: 'sala', label: 'Local', type: 'text' },
  ];

  // ── Operações ──
  groupBySala = true;
  opState: TableState = { page:1, limit:10, sort:'data', direction:'desc', search:'' };
  opFilters: Record<string, ColumnFilterState> = {};
  opRows = signal<any[]>([]); opMeta = signal<PaginationMeta|null>(null); opLoading = signal(true);
  opSearch = '';
  reportFormat = '';

  // ── RDS ──
  rdsAnos = signal<number[]>([]);
  rdsMeses = signal<number[]>([]);
  rdsAno = '';
  rdsMes = '';

  // ── Anormalidades ──
  anomState: TableState = { page:1, limit:10, sort:'data', direction:'desc', search:'' };
  anomFilters: Record<string, ColumnFilterState> = {};
  anomRows = signal<any[]>([]); anomMeta = signal<PaginationMeta|null>(null); anomLoading = signal(true);
  anomSearch = '';
  anomReportFormat = '';

  ngOnInit(): void {
    this.loadOperacoes();
    this.loadAnormalidades();
    this.loadRdsAnos();
  }

  // ═══ OPERAÇÕES ═══

  loadOperacoes(): void {
    this.opLoading.set(true);
    this.opState.filters = buildFilters(this.opFilters);
    const endpoint = this.groupBySala
      ? '/api/admin/dashboard/operacoes'
      : '/api/admin/dashboard/operacoes/entradas';
    this.api.getList(endpoint, this.opState).subscribe({
      next: r => { this.opRows.set(r.data||[]); this.opMeta.set(r.meta||null); this.opLoading.set(false); },
      error: () => { this.opRows.set([]); this.opLoading.set(false); },
    });
  }

  onGroupChange(): void {
    this.opState.page = 1;
    this.opFilters = {};
    this.loadOperacoes();
  }

  toggleSessao(s: any): void {
    s['_exp'] = !s['_exp'];
    if (s['_exp'] && !s['_entradas']) {
      this.api.get<any>('/api/admin/dashboard/operacoes/entradas-sessao', { registro_id: s['id'] as number }).subscribe({
        next: (res: any) => {
          s['_entradas'] = res?.data ?? [];
          s['_is_plenario_principal'] = res?.is_plenario_principal ?? false;
          this.opRows.set([...this.opRows()]);
        },
        error: () => { s['_entradas'] = []; this.opRows.set([...this.opRows()]); },
      });
    }
  }

  openEntrada(e: any): void {
    const id = e['id'];
    if (id) window.open(`/admin/operacao/detalhe?entrada_id=${id}`, '_blank');
  }

  onOpSortEvt(e: { sort: string; direction: string }): void {
    this.opState.sort = e.sort; this.opState.direction = e.direction; this.opState.page = 1;
    this.loadOperacoes();
  }
  onOpFilterEvt(e: { key: string; state: ColumnFilterState | null }): void {
    if (e.state) this.opFilters[e.key] = e.state;
    else delete this.opFilters[e.key];
    this.opState.page = 1;
    this.loadOperacoes();
  }
  onOpSearch(): void {
    clearTimeout(this.dOp);
    this.dOp = setTimeout(() => { this.opState.search = this.opSearch; this.opState.page = 1; this.loadOperacoes(); }, 400);
  }

  gerarRelatorioOp(): void {
    const endpoint = this.groupBySala
      ? '/api/admin/dashboard/operacoes/relatorio'
      : '/api/admin/dashboard/operacoes/entradas/relatorio';
    this.api.downloadReport(endpoint, buildReportParams(this.reportFormat, this.opState.sort, this.opState.direction, this.opState.search, this.opFilters));
  }

  // ═══ RDS ═══

  loadRdsAnos(): void {
    this.api.get<any>('/api/admin/operacoes/rds/anos').subscribe({
      next: (res: any) => { this.rdsAnos.set(res?.data || res?.anos || []); },
      error: () => {},
    });
  }

  onAnoChange(): void {
    this.rdsMes = '';
    this.rdsMeses.set([]);
    if (!this.rdsAno) return;
    this.api.get<any>('/api/admin/operacoes/rds/meses', { ano: +this.rdsAno }).subscribe({
      next: (res: any) => { this.rdsMeses.set(res?.data || res?.meses || []); },
      error: () => {},
    });
  }

  gerarRds(): void {
    if (!this.rdsAno || !this.rdsMes) return;
    this.api.getBlob('/api/admin/operacoes/rds/gerar', { ano: this.rdsAno, mes: this.rdsMes }).subscribe(blob => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `RDS_${this.rdsAno}-${String(this.rdsMes).padStart(2, '0')}.xlsx`;
      a.click();
      URL.revokeObjectURL(url);
    });
  }

  // ═══ ANORMALIDADES ═══

  loadAnormalidades(): void {
    this.anomLoading.set(true);
    this.anomState.filters = buildFilters(this.anomFilters);
    this.api.getList('/api/admin/dashboard/anormalidades/lista', this.anomState).subscribe({
      next: r => { this.anomRows.set(r.data||[]); this.anomMeta.set(r.meta||null); this.anomLoading.set(false); },
      error: () => { this.anomRows.set([]); this.anomLoading.set(false); },
    });
  }

  onAnomSortEvt(e: { sort: string; direction: string }): void {
    this.anomState.sort = e.sort; this.anomState.direction = e.direction; this.anomState.page = 1;
    this.loadAnormalidades();
  }
  onAnomFilterEvt(e: { key: string; state: ColumnFilterState | null }): void {
    if (e.state) this.anomFilters[e.key] = e.state;
    else delete this.anomFilters[e.key];
    this.anomState.page = 1;
    this.loadAnormalidades();
  }
  onAnomSearch(): void {
    clearTimeout(this.dAnom);
    this.dAnom = setTimeout(() => { this.anomState.search = this.anomSearch; this.anomState.page = 1; this.loadAnormalidades(); }, 400);
  }

  openAnormalidade(a: any): void {
    if (a['id']) window.open(`/admin/anormalidade/detalhe?id=${a['id']}`, '_blank');
  }

  gerarRelatorioAnom(): void {
    this.api.downloadReport('/api/admin/dashboard/anormalidades/lista/relatorio',
      buildReportParams(this.anomReportFormat, this.anomState.sort, this.anomState.direction, this.anomState.search, this.anomFilters));
  }

  // ═══ HELPERS (delegam para table.helpers.ts) ═══

  mesNome = mesNome;
  gd = getDistinct;
  asArr(v: unknown): any[] { return Array.isArray(v) ? v : []; }
  truncate(v: unknown, max: number): string { const s = String(v || ''); return s.length > max ? s.substring(0, max) + '...' : s; }

  formatEvento(s: any): string {
    const evento = s['ultimo_evento'] || '';
    const comissao = s['comissao_nome'] || '';
    if (!evento) return '';
    if (!comissao) return evento;
    // Extrair sigla (antes do primeiro " - ")
    const idx = comissao.indexOf(' - ');
    const sigla = idx >= 0 ? comissao.substring(0, idx).trim() : comissao.trim();
    return sigla + ' - ' + evento;
  }
}
