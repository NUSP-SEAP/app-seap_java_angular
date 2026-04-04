import { ChangeDetectorRef, Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService, ListParams } from '../../core/services/api.service';
import { PaginationMeta } from '../../core/models/user.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef, ColumnFilterState } from '../../shared/components/column-filter.component';
import { getDistinct, buildFilters } from '../../core/helpers/table.helpers';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FmtTimePipe } from '../../shared/pipes/fmt-time.pipe';

interface TableState extends ListParams {
  page: number; limit: number; sort: string; direction: string; search: string;
}

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [RouterLink, FormsModule, PaginationComponent, ColumnFilterComponent, FmtDatePipe, FmtTimePipe],
  template: `
    <h1>Painel Administrativo</h1>

    <!-- Cards de navegação -->
    <div class="grid-cards">
      <a routerLink="/home" class="card-custom card-link">Página Inicial dos Operadores</a>
      <a routerLink="/admin/novo-operador" class="card-custom card-link">Cadastro de Operador</a>
      <a class="card-custom card-link card-disabled">Escala Semanal</a>
      <a routerLink="/admin/operacoes" class="card-custom card-link">Operações de Áudio</a>
      <a routerLink="/admin/form-edit" class="card-custom card-link">Edição de Formulários</a>
      <a class="card-custom card-link card-disabled">Dashboard Metabase (teste)</a>
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
            <th style="width:110px">Alterar Senha</th>
          </tr></thead>
          <tbody>
            @if (opRows().length === 0) {
              <tr><td colspan="3" class="empty-state">{{ opLoading() ? 'Carregando...' : 'Nenhum operador encontrado.' }}</td></tr>
            } @else {
              @for (op of opRows(); track op['id']) {
                <tr>
                  <td><strong>{{ op['nome_completo'] || op['nome'] }}</strong></td>
                  <td>{{ op['email'] }}</td>
                  <td>
                    <button class="btn-xs" (click)="openSenhaPopup(op)">Alterar</button>
                    @if (senhaPopupOp === op) {
                      <div class="senha-overlay" (click)="closeSenhaPopup()"></div>
                      <div class="senha-popup">
                        <label>Nova Senha</label>
                        <input type="password" [(ngModel)]="senhaNova" placeholder="Mínimo 4 caracteres">
                        <label>Confirmar Senha</label>
                        <input type="password" [(ngModel)]="senhaConfirm" placeholder="Repita a senha">
                        <div class="senha-popup-actions">
                          <button class="btn-xs" (click)="closeSenhaPopup()">Cancelar</button>
                          <button class="btn-xs btn-xs-primary" (click)="confirmarAlterarSenha(op)" [disabled]="senhaSaving">
                            {{ senhaSaving ? 'Salvando...' : 'Alterar' }}
                          </button>
                        </div>
                      </div>
                    }
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <app-pagination [meta]="opMeta()!" (pageChange)="opState.page = $event; loadOperadores()" (limitChange)="opState.limit = $event; opState.page = 1; loadOperadores()" />
    </section>

    <!-- ═══ Checklists ═══ -->
    <section>
      <div class="section-header">
        <h2>Verificação de Plenários</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="chkSearch" (input)="onChkSearch()" placeholder="Buscar..." class="search-input">
          <button class="btn-report" (click)="downloadReport('/api/admin/dashboard/checklists/relatorio', 'pdf')">PDF</button>
          <button class="btn-report" (click)="downloadReport('/api/admin/dashboard/checklists/relatorio', 'docx')">DOCX</button>
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th style="width:30px"></th>
            <th>
              <app-column-filter [col]="chkCols[0]"
                [distinctValues]="getDistinct(chkMeta(), 'sala')"
                [currentSort]="chkState.sort" [currentDir]="chkState.direction"
                (sortChange)="onChkSort($event)" (filterChange)="onChkFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="chkCols[1]"
                [distinctValues]="getDistinct(chkMeta(), 'data')"
                [currentSort]="chkState.sort" [currentDir]="chkState.direction"
                (sortChange)="onChkSort($event)" (filterChange)="onChkFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="chkCols[2]"
                [distinctValues]="getDistinct(chkMeta(), 'nome')"
                [currentSort]="chkState.sort" [currentDir]="chkState.direction"
                (sortChange)="onChkSort($event)" (filterChange)="onChkFilter($event)" />
            </th>
            <th>Início</th>
            <th>Término</th>
            <th>Duração</th>
            <th>
              <app-column-filter [col]="chkCols[3]"
                [distinctValues]="getDistinct(chkMeta(), 'turno')"
                [currentSort]="chkState.sort" [currentDir]="chkState.direction"
                (sortChange)="onChkSort($event)" (filterChange)="onChkFilter($event)" />
            </th>
            <th>Ação</th>
          </tr></thead>
          <tbody>
            @if (chkRows().length === 0) {
              <tr><td colspan="9" class="empty-state">{{ chkLoading() ? 'Carregando...' : 'Nenhum checklist encontrado.' }}</td></tr>
            } @else {
              @for (chk of chkRows(); track chk['id']) {
                <tr>
                  <td><button class="btn-toggle" (click)="toggleAccordion(chk)">{{ chk['_expanded'] ? '▼' : '▶' }}</button></td>
                  <td><strong>{{ chk['sala_nome'] || chk['sala'] }}</strong></td>
                  <td>{{ chk['data'] | fmtDate }}</td>
                  <td>{{ chk['operador_nome'] }}</td>
                  <td>{{ chk['hora_inicio_testes'] | fmtTime }}</td>
                  <td>{{ chk['hora_termino_testes'] | fmtTime }}</td>
                  <td>{{ calcDuracao(chk) }}</td>
                  <td [class]="chk['status'] === 'Falha' ? 'badge-falha' : 'badge-ok'">{{ chk['status'] || 'Ok' }}</td>
                  <td><button class="btn-xs" (click)="openChecklistDetail(chk)">Formulário</button></td>
                </tr>
                @if (chk['_expanded']) {
                  <tr class="accordion-row">
                    <td colspan="9">
                      <strong class="accordion-title">Detalhes da Verificação:</strong>
                      @if (!chk['itens']) {
                        <p class="text-muted-sm">Carregando...</p>
                      } @else if (asArray(chk['itens']).length === 0) {
                        <p class="text-muted-sm">Nenhum item encontrado.</p>
                      } @else {
                        <table class="sub-table">
                          <thead><tr><th>Item verificado</th><th>Status</th><th>Descrição</th></tr></thead>
                          <tbody>
                            @for (it of asArray(chk['itens']); track it['id'] || $index) {
                              <tr>
                                <td>{{ it['item_nome'] || it['item'] }}</td>
                                <td [class]="it['tipo_widget'] === 'text' ? '' : (it['status'] === 'Falha' ? 'badge-falha' : 'badge-ok')">{{ it['tipo_widget'] === 'text' ? 'Texto' : it['status'] }}</td>
                                <td>{{ it['descricao_falha'] || it['valor_texto'] || '-' }}</td>
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
      <app-pagination [meta]="chkMeta()!" (pageChange)="chkState.page = $event; loadChecklists()" (limitChange)="chkState.limit = $event; chkState.page = 1; loadChecklists()" />
    </section>
  `,
  styles: [`
    .grid-cards { display:grid; grid-template-columns:repeat(3,1fr); gap:12px; margin-bottom:28px; }
    .card-link { display:flex; align-items:center; padding:16px 20px; text-decoration:none; color:var(--text); font-weight:600; font-size:.95rem; transition:box-shadow .15s; cursor:pointer; &:hover{box-shadow:0 4px 12px rgba(0,0,0,.1);} }
    .card-disabled { opacity:.6; cursor:default; &:hover{box-shadow:none;} }
    section { margin-bottom:28px; }
    .btn-xs-primary { background:var(--primary) !important; color:#fff !important; border-color:var(--primary) !important; }
    .btn-xs-primary:hover { background:var(--primary-hover) !important; }
    .btn-xs-primary:disabled { opacity:.5; cursor:not-allowed; }
    .senha-overlay {
      position:fixed; inset:0; z-index:99; background:rgba(0,0,0,.25);
    }
    .senha-popup {
      position:fixed; left:50%; top:50%; transform:translate(-50%,-50%); z-index:100;
      background:#fff; border:1px solid var(--border); border-radius:10px;
      box-shadow:0 12px 32px rgba(0,0,0,.18); padding:20px 24px; width:300px;
      display:flex; flex-direction:column; gap:8px;
      label { font-size:.8rem; font-weight:600; color:var(--muted); }
      input { font-size:.875rem; padding:8px 10px; }
    }
    .senha-popup-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:8px; }
  `],
})
export class AdminDashboardComponent implements OnInit {
  private api = inject(ApiService);
  private cdr = inject(ChangeDetectorRef);
  private debounceOp: any; private debounceChk: any;

  // ── Column definitions ──
  opCols: ColumnFilterDef[] = [
    { key: 'nome', label: 'Nome', type: 'text' },
    { key: 'email', label: 'E-mail', type: 'text' },
  ];
  chkCols: ColumnFilterDef[] = [
    { key: 'sala', label: 'Local', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
    { key: 'nome', label: 'Verificado por', type: 'text' },
    { key: 'turno', label: 'Status', type: 'text', sortable: false },
  ];

  // ── Operadores ──
  opState: TableState = { page:1, limit:10, sort:'nome', direction:'asc', search:'' };
  opFilters: Record<string, ColumnFilterState> = {};
  opRows = signal<Record<string,unknown>[]>([]); opMeta = signal<PaginationMeta|null>(null); opLoading = signal(true);
  opSearch = '';

  // ── Checklists ──
  chkState: TableState = { page:1, limit:10, sort:'data', direction:'desc', search:'' };
  chkFilters: Record<string, ColumnFilterState> = {};
  chkRows = signal<Record<string,unknown>[]>([]); chkMeta = signal<PaginationMeta|null>(null); chkLoading = signal(true);
  chkSearch = '';

  // ── Alterar Senha ──
  senhaPopupOp: Record<string,unknown> | null = null;
  senhaNova = '';
  senhaConfirm = '';
  senhaSaving = false;

  openSenhaPopup(op: Record<string,unknown>): void {
    this.senhaPopupOp = op;
    this.senhaNova = '';
    this.senhaConfirm = '';
  }

  closeSenhaPopup(): void {
    this.senhaPopupOp = null;
  }

  confirmarAlterarSenha(op: Record<string,unknown>): void {
    if (this.senhaNova.length < 4) { alert('A senha deve ter pelo menos 4 caracteres.'); return; }
    if (this.senhaNova !== this.senhaConfirm) { alert('As senhas não conferem.'); return; }
    this.senhaSaving = true;
    this.api.post<any>(`/api/admin/operador/${op['id']}/alterar-senha`, { nova_senha: this.senhaNova }).subscribe({
      next: (res: any) => {
        this.senhaSaving = false;
        this.senhaPopupOp = null;
        this.cdr.detectChanges();
        alert(res.ok ? 'Senha alterada com sucesso!' : (res.message || 'Erro ao alterar senha.'));
      },
      error: (err: any) => {
        this.senhaSaving = false;
        this.senhaPopupOp = null;
        this.cdr.detectChanges();
        alert(err?.error?.message || 'Erro ao alterar senha.');
      },
    });
  }

  ngOnInit(): void { this.loadOperadores(); this.loadChecklists(); }

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

  // ── Checklists ──
  loadChecklists(): void {
    this.chkLoading.set(true);
    this.chkState.filters = buildFilters(this.chkFilters);
    this.api.getList('/api/admin/dashboard/checklists', this.chkState).subscribe({
      next: r => { this.chkRows.set(r.data||[]); this.chkMeta.set(r.meta||null); this.chkLoading.set(false); },
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
  onChkSearch(): void { clearTimeout(this.debounceChk); this.debounceChk = setTimeout(() => { this.chkState.search=this.chkSearch; this.chkState.page=1; this.loadChecklists(); }, 400); }

  toggleAccordion(row: Record<string,unknown>): void {
    row['_expanded'] = !row['_expanded'];
    if (row['_expanded'] && !row['itens']) {
      this.api.get<any>('/api/admin/checklist/detalhe', { checklist_id: row['id'] as number }).subscribe({
        next: (res: any) => {
          const data = res?.data ?? res;
          row['itens'] = data?.itens ?? [];
          this.chkRows.set([...this.chkRows()]);
        },
        error: () => { row['itens'] = []; this.chkRows.set([...this.chkRows()]); },
      });
    }
  }
  openChecklistDetail(chk: Record<string,unknown>): void {
    window.open(`/admin/checklist/detalhe?checklist_id=${chk['id']}`, '_blank');
  }
  asArray(v: unknown): any[] { return Array.isArray(v) ? v : []; }

  calcDuracao(chk: Record<string, unknown>): string {
    const inicio = String(chk['inicio'] || chk['hora_inicio_testes'] || '');
    const termino = String(chk['termino'] || chk['hora_termino_testes'] || '');
    if (!inicio || !termino) return '-';
    const toSec = (t: string) => { const p = t.split(':'); return (+p[0]) * 3600 + (+p[1]) * 60 + (+p[2] || 0); };
    const diff = toSec(termino) - toSec(inicio);
    if (diff <= 0) return '-';
    const h = Math.floor(diff / 3600);
    const m = Math.floor((diff % 3600) / 60);
    const s = diff % 60;
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  // ── Relatórios ──
  downloadReport(endpoint: string, format: string): void { this.api.downloadReport(endpoint, { format }); }

  // ── Helpers (delegam para table.helpers.ts) ──
  getDistinct = getDistinct;

}
