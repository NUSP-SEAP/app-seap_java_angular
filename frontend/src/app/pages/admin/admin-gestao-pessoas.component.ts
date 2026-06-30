import { Component, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService, ListParams } from '../../core/services/api.service';
import { PaginationMeta } from '../../core/models/user.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef, ColumnFilterState } from '../../shared/components/column-filter.component';
import { getDistinct, buildFilters } from '../../core/helpers/table.helpers';
import { AuthService } from '../../core/services/auth.service';

interface TableState extends ListParams {
  page: number; limit: number; sort: string; direction: string; search: string;
}

@Component({
  selector: 'app-admin-gestao-pessoas',
  standalone: true,
  imports: [RouterLink, FormsModule, PaginationComponent, ColumnFilterComponent],
  template: `
    <h1>Gestão de pessoas</h1>
    <a routerLink="/admin" class="back-link">&larr; Voltar ao Painel</a>

    <!-- Cards de navegação -->
    <div class="grid-cards">
      <a routerLink="/admin/novo-operador" class="card-custom card-link">Cadastro de Operador</a>
      <a routerLink="/admin/novo-tecnico" class="card-custom card-link">Cadastro de Técnico</a>
      <a routerLink="/admin/avisos-sala" class="card-custom card-link">Inserir Avisos</a>
      <a routerLink="/admin/escala" class="card-custom card-link">Escala Semanal</a>
      <a routerLink="/admin/ponto" class="card-custom card-link">Ponto e Banco</a>
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
            <th style="width:110px">Ação</th>
          </tr></thead>
          <tbody>
            @if (opRows().length === 0) {
              <tr><td colspan="3" class="empty-state">{{ opLoading() ? 'Carregando...' : 'Nenhum operador encontrado.' }}</td></tr>
            } @else {
              @for (op of opRows(); track op['id']) {
                <tr>
                  <td><strong>{{ op['nome_completo'] || op['nome'] }}</strong></td>
                  <td>{{ op['email'] }}</td>
                  <td><button class="btn-xs" (click)="abrirPerfil('operador', op)">Perfil</button></td>
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
            <th style="width:110px">Ação</th>
          </tr></thead>
          <tbody>
            @if (tecRows().length === 0) {
              <tr><td colspan="3" class="empty-state">{{ tecLoading() ? 'Carregando...' : 'Nenhum técnico cadastrado.' }}</td></tr>
            } @else {
              @for (t of tecRows(); track t['id']) {
                <tr>
                  <td><strong>{{ t['nome_completo'] || t['nome'] }}</strong></td>
                  <td>{{ t['email'] }}</td>
                  <td><button class="btn-xs" (click)="abrirPerfil('tecnico', t)">Perfil</button></td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <app-pagination [meta]="tecMeta()!" (pageChange)="tecState.page = $event; loadTecnicos()" (limitChange)="tecState.limit = $event; tecState.page = 1; loadTecnicos()" />
    </section>

    <!-- ═══ Administradores do Sistema (somente o master) ═══ -->
    @if (isMaster()) {
      <section>
        <div class="section-header">
          <h2>Administradores do Sistema</h2>
          <div class="header-actions">
            <button class="btn-xs" (click)="novoAdmin()">Novo Admin</button>
          </div>
        </div>
        <div class="table-container">
          <table class="data-table">
            <thead><tr>
              <th>
                <app-column-filter [col]="admCols[0]"
                  [distinctValues]="getDistinct(admMeta(), 'nome')"
                  [currentSort]="admState.sort" [currentDir]="admState.direction"
                  (sortChange)="onAdmSort($event)" (filterChange)="onAdmFilter($event)" />
              </th>
              <th>
                <app-column-filter [col]="admCols[1]"
                  [distinctValues]="getDistinct(admMeta(), 'email')"
                  [currentSort]="admState.sort" [currentDir]="admState.direction"
                  (sortChange)="onAdmSort($event)" (filterChange)="onAdmFilter($event)" />
              </th>
              <th style="width:110px">Ação</th>
            </tr></thead>
            <tbody>
              @if (admRows().length === 0) {
                <tr><td colspan="3" class="empty-state">{{ admLoading() ? 'Carregando...' : 'Nenhum administrador encontrado.' }}</td></tr>
              } @else {
                @for (a of admRows(); track a['id']) {
                  <tr>
                    <td><strong>{{ a['nome_completo'] || a['nome'] }}</strong></td>
                    <td>{{ a['email'] }}</td>
                    <td><button class="btn-xs" (click)="abrirPerfil('administrador', a)">Perfil</button></td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
        <app-pagination [meta]="admMeta()!" (pageChange)="admState.page = $event; loadAdministradores()" (limitChange)="admState.limit = $event; admState.page = 1; loadAdministradores()" />
      </section>
    }
  `,
  styles: [`
    .grid-cards { display:grid; grid-template-columns:repeat(3,1fr); gap:12px; margin:16px 0 28px; }
    .card-link { display:flex; align-items:center; padding:16px 20px; text-decoration:none; color:var(--text); font-weight:600; font-size:.95rem; transition:box-shadow .15s; cursor:pointer; &:hover{box-shadow:0 4px 12px rgba(0,0,0,.1);} }
    section { margin-bottom:28px; }
  `],
})
export class AdminGestaoPessoasComponent implements OnInit {
  private api = inject(ApiService);
  private router = inject(Router);
  private auth = inject(AuthService);
  isMaster = this.auth.isMaster;
  private debounceOp: any; private debounceTec: any;

  // ── Column definitions ──
  opCols: ColumnFilterDef[] = [
    { key: 'nome', label: 'Nome', type: 'text' },
    { key: 'email', label: 'E-mail', type: 'text' },
  ];
  tecCols: ColumnFilterDef[] = [
    { key: 'nome', label: 'Nome', type: 'text' },
    { key: 'email', label: 'E-mail', type: 'text' },
  ];
  admCols: ColumnFilterDef[] = [
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

  // ── Administradores (somente master; tabela sem busca global) ──
  admState: TableState = { page:1, limit:10, sort:'nome', direction:'asc', search:'' };
  admFilters: Record<string, ColumnFilterState> = {};
  admRows = signal<Record<string,unknown>[]>([]); admMeta = signal<PaginationMeta|null>(null); admLoading = signal(true);

  // ── Navegação para o Perfil (operador ou técnico) ──
  abrirPerfil(tipo: 'operador' | 'tecnico' | 'administrador', row: Record<string,unknown>): void {
    this.router.navigate([`/admin/${tipo}/perfil`], { queryParams: { id: row['id'] } });
  }

  ngOnInit(): void { this.loadOperadores(); this.loadTecnicos(); if (this.isMaster()) this.loadAdministradores(); }

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

  // ── Administradores (somente master) ──
  loadAdministradores(): void {
    this.admLoading.set(true);
    this.admState.filters = buildFilters(this.admFilters);
    this.api.getList('/api/admin/dashboard/administradores', this.admState).subscribe({
      next: r => { this.admRows.set(r.data||[]); this.admMeta.set(r.meta||null); this.admLoading.set(false); },
      error: () => { this.admRows.set([]); this.admLoading.set(false); },
    });
  }
  onAdmSort(e: { sort: string; direction: string }): void {
    this.admState.sort = e.sort; this.admState.direction = e.direction; this.admState.page = 1;
    this.loadAdministradores();
  }
  onAdmFilter(e: { key: string; state: ColumnFilterState | null }): void {
    if (e.state) this.admFilters[e.key] = e.state;
    else delete this.admFilters[e.key];
    this.admState.page = 1;
    this.loadAdministradores();
  }
  novoAdmin(): void { this.router.navigate(['/admin/novo-admin']); }

  // ── Relatórios ──
  downloadReport(endpoint: string, format: string): void { this.api.downloadReport(endpoint, { format }); }

  // ── Helpers ──
  getDistinct = getDistinct;
}
