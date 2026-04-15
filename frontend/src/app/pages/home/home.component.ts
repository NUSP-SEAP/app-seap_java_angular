import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { ApiService, ListParams } from '../../core/services/api.service';
import { PaginationMeta } from '../../core/models/user.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef, ColumnFilterState } from '../../shared/components/column-filter.component';
import { getDistinct, buildFilters } from '../../core/helpers/table.helpers';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FmtTimePipe } from '../../shared/pipes/fmt-time.pipe';

interface TableState extends ListParams {
  page: number;
  limit: number;
  sort: string;
  direction: string;
}

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, PaginationComponent, ColumnFilterComponent, FmtDatePipe, FmtTimePipe],
  template: `
    <h1>Página Principal</h1>

    <div class="grid-cards">
      <a routerLink="/checklist" class="card-custom card-link">
        <strong>Formulário de Verificação de Plenários</strong>
        <span class="text-muted-sm">Abrir</span>
      </a>
      <a routerLink="/operacao" class="card-custom card-link">
        <strong>Registro de Operação de Áudio</strong>
        <span class="text-muted-sm">Abrir</span>
      </a>
      <a routerLink="/agenda" class="card-custom card-link">
        <strong>Agenda Legislativa</strong>
        <span class="text-muted-sm">Abrir</span>
      </a>
      @if (auth.isAdmin()) {
        <a routerLink="/admin" class="card-custom card-link card-admin">
          <strong>Painel Administrativo</strong>
          <span class="text-muted-sm">Voltar para Admin</span>
        </a>
      }
    </div>

    <!-- ═══ Meus Checklists ═══ -->
    <section>
      <div class="section-header">
        <h2>Verificação de Salas</h2>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead>
            <tr>
              <th><app-column-filter [col]="chkCols[0]" [distinctValues]="gd(chkMeta(),'sala')" [currentSort]="chkState.sort" [currentDir]="chkState.direction" (sortChange)="onChkSort($event)" (filterChange)="onChkFilter($event)" /></th>
              <th><app-column-filter [col]="chkCols[1]" [distinctValues]="gd(chkMeta(),'data')" [currentSort]="chkState.sort" [currentDir]="chkState.direction" (sortChange)="onChkSort($event)" (filterChange)="onChkFilter($event)" /></th>
              <th style="text-align:center"><span class="sort-header" (click)="onChkSort({sort:'qtde_ok', direction: chkState.sort==='qtde_ok' && chkState.direction==='asc' ? 'desc' : 'asc'})">Qtde. OK <span class="sort-arrow">{{ chkState.sort==='qtde_ok' ? (chkState.direction==='asc' ? '\u25B2' : '\u25BC') : '\u25BD' }}</span></span></th>
              <th style="text-align:center"><span class="sort-header" (click)="onChkSort({sort:'qtde_falha', direction: chkState.sort==='qtde_falha' && chkState.direction==='asc' ? 'desc' : 'asc'})">Qtde. Falha <span class="sort-arrow">{{ chkState.sort==='qtde_falha' ? (chkState.direction==='asc' ? '\u25B2' : '\u25BC') : '\u25BD' }}</span></span></th>
              <th>Ação</th>
            </tr>
          </thead>
          <tbody>
            @if (chkLoading()) {
              <tr><td colspan="5" class="empty-state">Carregando verificações...</td></tr>
            } @else if (chkRows().length === 0) {
              <tr><td colspan="5" class="empty-state">Nenhuma verificação encontrada.</td></tr>
            } @else {
              @for (chk of chkRows(); track chk['id']) {
                <tr>
                  <td><strong>{{ chk['sala_nome'] }}</strong></td>
                  <td>{{ chk['data'] | fmtDate }}</td>
                  <td [style.color]="intVal(chk, 'qtde_ok') > 0 ? 'var(--color-green)' : '#334155'"
                      style="text-align:center; font-weight:bold">{{ chk['qtde_ok'] || 0 }}</td>
                  <td [style.color]="intVal(chk, 'qtde_falha') > 0 ? 'var(--color-red)' : '#334155'"
                      style="text-align:center; font-weight:bold">{{ chk['qtde_falha'] || 0 }}</td>
                  <td><button class="btn-xs" (click)="openChecklist(chk)">Formulário</button></td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <div class="table-footer">
        <button class="btn-report" (click)="gerarRelatorioChecklists()">Gerar Relatório</button>
        <app-pagination [meta]="chkMeta()!" (pageChange)="chkState.page = $event; loadChecklists()" (limitChange)="chkState.limit = $event; chkState.page = 1; loadChecklists()" />
      </div>
    </section>

    <!-- ═══ Minhas Operações ═══ -->
    <section>
      <div class="section-header">
        <h2>Registros de Operação de Áudio</h2>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead>
            <tr>
              <th><app-column-filter [col]="opCols[0]" [distinctValues]="gd(opMeta(),'sala')" [currentSort]="opState.sort" [currentDir]="opState.direction" (sortChange)="onOpSort($event)" (filterChange)="onOpFilter($event)" /></th>
              <th><app-column-filter [col]="opCols[1]" [distinctValues]="gd(opMeta(),'data')" [currentSort]="opState.sort" [currentDir]="opState.direction" (sortChange)="onOpSort($event)" (filterChange)="onOpFilter($event)" /></th>
              <th style="text-align:center"><span class="sort-header" (click)="onOpSort({sort:'hora_entrada', direction: opState.sort==='hora_entrada' && opState.direction==='asc' ? 'desc' : 'asc'})">Início Operação <span class="sort-arrow">{{ opState.sort==='hora_entrada' ? (opState.direction==='asc' ? '\u25B2' : '\u25BC') : '\u25BD' }}</span></span></th>
              <th style="text-align:center"><span class="sort-header" (click)="onOpSort({sort:'hora_saida', direction: opState.sort==='hora_saida' && opState.direction==='asc' ? 'desc' : 'asc'})">Fim Operação <span class="sort-arrow">{{ opState.sort==='hora_saida' ? (opState.direction==='asc' ? '\u25B2' : '\u25BC') : '\u25BD' }}</span></span></th>
              <th style="text-align:center"><app-column-filter [col]="opCols[2]" [distinctValues]="gd(opMeta(),'anormalidade')" [currentSort]="opState.sort" [currentDir]="opState.direction" (sortChange)="onOpSort($event)" (filterChange)="onOpFilter($event)" /></th>
              <th>Ação</th>
            </tr>
          </thead>
          <tbody>
            @if (opLoading()) {
              <tr><td colspan="6" class="empty-state">Carregando operações...</td></tr>
            } @else if (opRows().length === 0) {
              <tr><td colspan="6" class="empty-state">Nenhuma operação encontrada.</td></tr>
            } @else {
              @for (op of opRows(); track op['id'] || op['entrada_id']) {
                <tr>
                  <td><strong>{{ op['sala'] || op['sala_nome'] }}</strong></td>
                  <td>{{ op['data'] | fmtDate }}</td>
                  <td style="text-align:center">{{ op['hora_entrada'] | fmtTime }}</td>
                  <td style="text-align:center">{{ op['hora_saida'] | fmtTime }}</td>
                  <td style="text-align:center">
                    @if (op['anormalidade'] || op['houve_anormalidade']) {
                      @if (op['anormalidade_id']) {
                        <button class="btn-xs btn-anom-sim" (click)="openAnormalidade(op)">SIM</button>
                      } @else {
                        <span class="badge-falha" style="font-weight:700">SIM</span>
                      }
                    } @else {
                      <span class="badge-ok">Não</span>
                    }
                  </td>
                  <td><button class="btn-xs" (click)="openOperacao(op)">Formulário</button></td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <div class="table-footer">
        <button class="btn-report" (click)="gerarRelatorioOperacoes()">Gerar Relatório</button>
        <app-pagination [meta]="opMeta()!" (pageChange)="opState.page = $event; loadOperacoes()" (limitChange)="opState.limit = $event; opState.page = 1; loadOperacoes()" />
      </div>
    </section>
  `,
  styles: [`
    .table-footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-top: 8px;
    }
    .grid-cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 16px;
      margin-bottom: 32px;
    }
    .card-link {
      display: flex;
      flex-direction: column;
      gap: 4px;
      text-decoration: none;
      color: var(--text);
      transition: box-shadow .15s;
      &:hover { box-shadow: 0 4px 12px rgba(0,0,0,.1); }
    }
    .card-admin {
      border-color: #cbd5e1;
      background-color: #f8fafc;
      strong { color: var(--muted); }
    }
    section { margin-bottom: 32px; }
    .sort-header {
      cursor: pointer; user-select: none; white-space: nowrap;
      &:hover { color: var(--primary); }
    }
    .sort-arrow { font-size: .7rem; }
    .btn-anom-sim {
      background: #fef2f2;
      border-color: #fca5a5;
      color: #b91c1c;
      font-weight: 700;
      &:hover { background: #fee2e2; }
    }
  `],
})
export class HomeComponent implements OnInit {
  auth = inject(AuthService);
  private api = inject(ApiService);

  // ── Column defs ──
  chkCols: ColumnFilterDef[] = [
    { key: 'sala', label: 'Sala', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
  ];
  opCols: ColumnFilterDef[] = [
    { key: 'sala', label: 'Sala', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
    { key: 'anormalidade', label: 'Anormalidade?', type: 'text' },
  ];

  // ── Checklists state ──
  chkState: TableState = { page: 1, limit: 10, sort: 'data', direction: 'desc' };
  chkFilters: Record<string, ColumnFilterState> = {};
  chkRows = signal<Record<string, unknown>[]>([]);
  chkMeta = signal<PaginationMeta | null>(null);
  chkLoading = signal(true);

  // ── Operações state ──
  opState: TableState = { page: 1, limit: 10, sort: 'data', direction: 'desc' };
  opFilters: Record<string, ColumnFilterState> = {};
  opRows = signal<Record<string, unknown>[]>([]);
  opMeta = signal<PaginationMeta | null>(null);
  opLoading = signal(true);

  ngOnInit(): void {
    if (!this.auth.user()) {
      this.auth.whoAmI().subscribe(() => { this.loadChecklists(); this.loadOperacoes(); });
    } else {
      this.loadChecklists();
      this.loadOperacoes();
    }
  }

  // ── Checklists ──

  loadChecklists(): void {
    this.chkLoading.set(true);
    this.chkState.filters = buildFilters(this.chkFilters);
    this.api.getList('/api/operador/meus-checklists', this.chkState).subscribe({
      next: res => {
        this.chkRows.set(res.data || []);
        this.chkMeta.set(res.meta || null);
        this.chkLoading.set(false);
      },
      error: () => { this.chkRows.set([]); this.chkLoading.set(false); },
    });
  }

  onChkSort(e: { sort: string; direction: string }): void {
    this.chkState.sort = e.sort; this.chkState.direction = e.direction; this.chkState.page = 1;
    this.loadChecklists();
  }
  onChkFilter(e: { key: string; state: ColumnFilterState | null }): void {
    if (e.state) this.chkFilters[e.key] = e.state;
    else delete this.chkFilters[e.key];
    this.chkState.page = 1;
    this.loadChecklists();
  }

  openChecklist(chk: Record<string, unknown>): void {
    if (chk['id']) window.open(`/checklist/edit?checklist_id=${chk['id']}`, '_blank');
  }

  gerarRelatorioChecklists(): void {
    const params: Record<string, string> = {
      sort: this.chkState.sort, direction: this.chkState.direction,
    };
    const filters = buildFilters(this.chkFilters);
    if (Object.keys(filters).length) params['filters'] = JSON.stringify(filters);
    this.api.openPdfInline('/api/operador/meus-checklists/relatorio', params);
  }

  // ── Operações ──

  loadOperacoes(): void {
    this.opLoading.set(true);
    this.opState.filters = buildFilters(this.opFilters);
    this.api.getList('/api/operador/minhas-operacoes', this.opState).subscribe({
      next: res => {
        this.opRows.set(res.data || []);
        this.opMeta.set(res.meta || null);
        this.opLoading.set(false);
      },
      error: () => { this.opRows.set([]); this.opLoading.set(false); },
    });
  }

  onOpSort(e: { sort: string; direction: string }): void {
    this.opState.sort = e.sort; this.opState.direction = e.direction; this.opState.page = 1;
    this.loadOperacoes();
  }
  onOpFilter(e: { key: string; state: ColumnFilterState | null }): void {
    if (e.state) this.opFilters[e.key] = e.state;
    else delete this.opFilters[e.key];
    this.opState.page = 1;
    this.loadOperacoes();
  }

  openOperacao(op: Record<string, unknown>): void {
    const id = op['id'] || op['entrada_id'];
    if (id) window.open(`/operacao/edit?entrada_id=${id}`, '_blank');
  }

  gerarRelatorioOperacoes(): void {
    const params: Record<string, string> = {
      sort: this.opState.sort, direction: this.opState.direction,
    };
    const filters = buildFilters(this.opFilters);
    if (Object.keys(filters).length) params['filters'] = JSON.stringify(filters);
    this.api.openPdfInline('/api/operador/minhas-operacoes/relatorio', params);
  }

  openAnormalidade(op: Record<string, unknown>): void {
    if (op['anormalidade_id']) window.open(`/anormalidade/detalhe?id=${op['anormalidade_id']}`, '_blank');
  }

  // ── Helpers ──

  intVal(obj: Record<string, unknown>, key: string): number {
    const v = obj[key];
    return typeof v === 'number' ? v : parseInt(String(v || '0'), 10);
  }

  gd = getDistinct;
}
