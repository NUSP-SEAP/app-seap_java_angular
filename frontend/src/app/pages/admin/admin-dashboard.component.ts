import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService, ListParams } from '../../core/services/api.service';
import { PaginationMeta } from '../../core/models/user.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef, ColumnFilterState } from '../../shared/components/column-filter.component';
import { getDistinct, buildFilters } from '../../core/helpers/table.helpers';
import { hojeDdMm } from '../../core/helpers/date.helpers';

interface TableState extends ListParams {
  page: number; limit: number; sort: string; direction: string; search: string;
}

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [RouterLink, FormsModule, PaginationComponent, ColumnFilterComponent],
  template: `
    <h1>Painel Administrativo</h1>

    <!-- Cards de navegação (3 colunas × 2 linhas) -->
    <div class="grid-cards">
      <a routerLink="/home" class="card-custom card-link">Página Inicial dos Operadores</a>
      <a routerLink="/admin/operacao-audio" class="card-custom card-link">Operação de Áudio</a>
      <a routerLink="/admin/agenda" class="card-custom card-link">Agenda Legislativa</a>
      <a routerLink="/tecnico" class="card-custom card-link">Página Inicial dos Técnicos</a>
      <a routerLink="/admin/area-tecnica" class="card-custom card-link">Área Técnica</a>
      <span class="card-slot-empty"></span>
    </div>

    <!-- ═══ Operadores ═══ -->
    <section>
      <div class="section-header">
        <h2>Operadores de Áudio</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="opSearch" (input)="onOpSearch()" placeholder="Buscar..." class="search-input">
          <button class="btn-report" (click)="downloadReport('/api/admin/dashboard/operadores/relatorio', 'pdf')">PDF</button>
          <button class="btn-report" (click)="downloadReport('/api/admin/dashboard/operadores/relatorio', 'docx')">DOCX</button>
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th>
              <app-column-filter [col]="opCols[0]"
                [distinctValues]="getDistinct(opMeta(), 'nome')"
                [currentSort]="opState.sort" [currentDir]="opState.direction"
                (sortChange)="onOpSort($event)" (filterChange)="onOpFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="opCols[1]"
                [distinctValues]="getDistinct(opMeta(), 'email')"
                [currentSort]="opState.sort" [currentDir]="opState.direction"
                (sortChange)="onOpSort($event)" (filterChange)="onOpFilter($event)" />
            </th>
            <th style="width:130px">Turno</th>
            <th style="width:90px" title="Apto a operar no Plenário Principal">Apto PP</th>
            <th style="width:90px" title="Operador fixo do Plenário Principal">Fixo PP</th>
            <th style="width:80px">Escala</th>
          </tr></thead>
          <tbody>
            @if (opRows().length === 0) {
              <tr><td colspan="6" class="empty-state">{{ opLoading() ? 'Carregando...' : 'Nenhum operador encontrado.' }}</td></tr>
            } @else {
              @for (op of opRows(); track op['id']) {
                <tr>
                  <td><strong>{{ op['nome_completo'] || op['nome'] }}</strong></td>
                  <td>{{ op['email'] }}</td>
                  <td class="turno-cell" style="text-align:center">
                    <select [value]="op['turno'] || 'M'" (change)="setTurno(op, $any($event.target).value)" class="turno-select">
                      <option value="M">Matutino</option>
                      <option value="V">Vespertino</option>
                    </select>
                  </td>
                  <td style="text-align:center">
                    <input type="checkbox" [checked]="op['plenario_principal'] === true || op['plenario_principal'] === 1"
                      (change)="togglePlenario(op)" style="cursor:pointer; width:18px; height:18px">
                  </td>
                  <td style="text-align:center">
                    <input type="checkbox"
                      [checked]="op['plenario_principal_fixo'] === true || op['plenario_principal_fixo'] === 1"
                      [disabled]="!(op['plenario_principal'] === true || op['plenario_principal'] === 1)"
                      (change)="togglePlenarioFixo(op)"
                      style="cursor:pointer; width:18px; height:18px">
                  </td>
                  <td style="text-align:center">
                    <input type="checkbox" [checked]="op['participa_escala'] === true || op['participa_escala'] === 1"
                      (change)="toggleEscala(op)" style="cursor:pointer; width:18px; height:18px">
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <app-pagination [meta]="opMeta()!" (pageChange)="opState.page = $event; loadOperadores()" (limitChange)="opState.limit = $event; opState.page = 1; loadOperadores()" />
    </section>

    <!-- ═══ Técnicos ═══ -->
    <section>
      <div class="section-header">
        <h2>Técnicos</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="tecSearch" (input)="onTecSearch()" placeholder="Buscar..." class="search-input">
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th>
              <app-column-filter [col]="tecCols[0]"
                [distinctValues]="getDistinct(tecMeta(), 'nome')"
                [currentSort]="tecState.sort" [currentDir]="tecState.direction"
                (sortChange)="onTecSort($event)" (filterChange)="onTecFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="tecCols[1]"
                [distinctValues]="getDistinct(tecMeta(), 'email')"
                [currentSort]="tecState.sort" [currentDir]="tecState.direction"
                (sortChange)="onTecSort($event)" (filterChange)="onTecFilter($event)" />
            </th>
          </tr></thead>
          <tbody>
            @if (tecRows().length === 0) {
              <tr><td colspan="2" class="empty-state">{{ tecLoading() ? 'Carregando...' : 'Nenhum técnico cadastrado.' }}</td></tr>
            } @else {
              @for (t of tecRows(); track t['id']) {
                <tr>
                  <td><strong>{{ t['nome_completo'] || t['nome'] }}</strong></td>
                  <td>{{ t['email'] }}</td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <app-pagination [meta]="tecMeta()!" (pageChange)="tecState.page = $event; loadTecnicos()" (limitChange)="tecState.limit = $event; tecState.page = 1; loadTecnicos()" />
    </section>
  `,
  styles: [`
    .grid-cards { display:grid; grid-template-columns:repeat(3,1fr); gap:12px; margin-bottom:28px; }
    .card-link { display:flex; align-items:center; padding:16px 20px; text-decoration:none; color:var(--text); font-weight:600; font-size:.95rem; transition:box-shadow .15s; cursor:pointer; &:hover{box-shadow:0 4px 12px rgba(0,0,0,.1);} }
    .card-slot-empty { display:block; }
    .card-disabled { opacity:.6; cursor:default; &:hover{box-shadow:none;} }
    section { margin-bottom:28px; }
    .btn-xs-primary { background:var(--primary) !important; color:#fff !important; border-color:var(--primary) !important; }
    .btn-xs-primary:hover { background:var(--primary-hover) !important; }
    .btn-xs-primary:disabled { opacity:.5; cursor:not-allowed; }
    .turno-cell { padding: 4px 16px !important; }
    .turno-select {
      appearance: none; -webkit-appearance: none; -moz-appearance: none;
      box-sizing: border-box; display: inline-block;
      padding: 0 18px 0 6px; height: 20px; line-height: 18px; min-height: 0;
      border: 1px solid var(--border); border-radius: 6px;
      background: var(--card) url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'><path d='M0 0l5 6 5-6z' fill='%23475569'/></svg>") no-repeat right 6px center;
      color: var(--text); font-size: .75rem; cursor: pointer;
      vertical-align: middle;
      &:hover { border-color: var(--primary); }
      &:focus { outline: none; border-color: var(--primary); }
    }
  `],
})
export class AdminDashboardComponent implements OnInit {
  private api = inject(ApiService);
  private debounceOp: any; private debounceTec: any;

  readonly hojeDdMm = hojeDdMm();

  // ── Column definitions ──
  opCols: ColumnFilterDef[] = [
    { key: 'nome', label: 'Nome', type: 'text' },
    { key: 'email', label: 'E-mail', type: 'text' },
  ];
  tecCols: ColumnFilterDef[] = [
    { key: 'nome', label: 'Nome', type: 'text' },
    { key: 'email', label: 'E-mail', type: 'text' },
  ];

  // ── Operadores ──
  opState: TableState = { page:1, limit:10, sort:'nome', direction:'asc', search:'' };
  opFilters: Record<string, ColumnFilterState> = {};
  opRows = signal<Record<string,unknown>[]>([]); opMeta = signal<PaginationMeta|null>(null); opLoading = signal(true);
  opSearch = '';

  // ── Técnicos ──
  tecState: TableState = { page:1, limit:10, sort:'nome', direction:'asc', search:'' };
  tecFilters: Record<string, ColumnFilterState> = {};
  tecRows = signal<Record<string,unknown>[]>([]); tecMeta = signal<PaginationMeta|null>(null); tecLoading = signal(true);
  tecSearch = '';

  // ── Toggle Plenário ──
  togglePlenario(op: Record<string,unknown>): void {
    // Update otimista: alterna localmente antes da chamada para feedback imediato
    // (sem isso, o [disabled] do "Fixo PP" só re-avaliaria após algum evento externo)
    const novoApto = !(op['plenario_principal'] === true || op['plenario_principal'] === 1);
    op['plenario_principal'] = novoApto ? 1 : 0;
    if (!novoApto) op['plenario_principal_fixo'] = 0;
    this.opRows.set([...this.opRows()]);

    this.api.patch<any>(`/api/admin/operador/${op['id']}/toggle-plenario`, {}).subscribe({
      next: (res: any) => {
        if (res.ok) {
          op['plenario_principal'] = res.plenario_principal ? 1 : 0;
          if (!res.plenario_principal) op['plenario_principal_fixo'] = 0;
          this.opRows.set([...this.opRows()]);
        }
      },
      error: () => {
        alert('Erro ao alterar flag de plenário.');
        this.loadOperadores();
      },
    });
  }

  togglePlenarioFixo(op: Record<string,unknown>): void {
    const novoFixo = !(op['plenario_principal_fixo'] === true || op['plenario_principal_fixo'] === 1);
    op['plenario_principal_fixo'] = novoFixo ? 1 : 0;
    this.opRows.set([...this.opRows()]);

    this.api.patch<any>(`/api/admin/operador/${op['id']}/toggle-plenario-fixo`, {}).subscribe({
      next: (res: any) => {
        if (res.ok) {
          op['plenario_principal_fixo'] = res.plenario_principal_fixo ? 1 : 0;
          this.opRows.set([...this.opRows()]);
        }
      },
      error: (err: any) => {
        const msg = err?.error?.message || 'Erro ao alterar flag de fixo do Plenário Principal.';
        alert(msg);
        this.loadOperadores();
      },
    });
  }

  toggleEscala(op: Record<string,unknown>): void {
    this.api.patch<any>(`/api/admin/operador/${op['id']}/toggle-escala`, {}).subscribe({
      next: (res: any) => {
        if (res.ok) op['participa_escala'] = res.participa_escala ? 1 : 0;
      },
      error: () => {
        alert('Erro ao alterar flag de escala.');
        this.loadOperadores();
      },
    });
  }

  setTurno(op: Record<string,unknown>, turno: string): void {
    this.api.patch<any>(`/api/admin/operador/${op['id']}/turno`, { turno }).subscribe({
      next: (res: any) => {
        if (res.ok) op['turno'] = res.turno;
      },
      error: () => {
        alert('Erro ao alterar turno do operador.');
        this.loadOperadores();
      },
    });
  }

  ngOnInit(): void { this.loadOperadores(); this.loadTecnicos(); }

  // ── Operadores ──
  loadOperadores(): void {
    this.opLoading.set(true);
    this.opState.filters = buildFilters(this.opFilters);
    this.api.getList('/api/admin/dashboard/operadores', this.opState).subscribe({
      next: r => { this.opRows.set(r.data||[]); this.opMeta.set(r.meta||null); this.opLoading.set(false); },
      error: () => { this.opRows.set([]); this.opLoading.set(false); },
    });
  }
  onOpSort(e: { sort: string; direction: string }): void {
    this.opState.sort = e.sort; this.opState.direction = e.direction; this.opState.page = 1;
    this.loadOperadores();
  }
  onOpFilter(e: { key: string; state: ColumnFilterState | null }): void {
    if (e.state) this.opFilters[e.key] = e.state;
    else delete this.opFilters[e.key];
    this.opState.page = 1;
    this.loadOperadores();
  }
  onOpSearch(): void { clearTimeout(this.debounceOp); this.debounceOp = setTimeout(() => { this.opState.search=this.opSearch; this.opState.page=1; this.loadOperadores(); }, 400); }

  // ── Técnicos ──
  loadTecnicos(): void {
    this.tecLoading.set(true);
    this.tecState.filters = buildFilters(this.tecFilters);
    this.api.getList('/api/admin/dashboard/tecnicos', this.tecState).subscribe({
      next: r => { this.tecRows.set(r.data||[]); this.tecMeta.set(r.meta||null); this.tecLoading.set(false); },
      error: () => { this.tecRows.set([]); this.tecLoading.set(false); },
    });
  }
  onTecSort(e: { sort: string; direction: string }): void {
    this.tecState.sort = e.sort; this.tecState.direction = e.direction; this.tecState.page = 1;
    this.loadTecnicos();
  }
  onTecFilter(e: { key: string; state: ColumnFilterState | null }): void {
    if (e.state) this.tecFilters[e.key] = e.state;
    else delete this.tecFilters[e.key];
    this.tecState.page = 1;
    this.loadTecnicos();
  }
  onTecSearch(): void { clearTimeout(this.debounceTec); this.debounceTec = setTimeout(() => { this.tecState.search=this.tecSearch; this.tecState.page=1; this.loadTecnicos(); }, 400); }

  // ── Relatórios ──
  downloadReport(endpoint: string, format: string): void { this.api.downloadReport(endpoint, { format }); }

  // ── Helpers (delegam para table.helpers.ts) ──
  getDistinct = getDistinct;

}
