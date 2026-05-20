import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, ListParams } from '../../core/services/api.service';
import { LookupService } from '../../core/services/lookup.service';
import { PaginationMeta } from '../../core/models/user.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef, ColumnFilterState } from '../../shared/components/column-filter.component';
import { getDistinct, buildFilters } from '../../core/helpers/table.helpers';
import { ToastService } from '../../shared/components/toast.component';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';

interface CienteRow { operador_id: string; operador_nome: string; ciente_em: string; }
interface AvisoRow {
  id: string;
  numero: number;
  sala_id: number;
  sala_nome: string;
  mensagem: string;
  duracao_dias: number;
  criado_em: string;
  expira_em: string;
  status: 'Ativo' | 'Expirado' | 'Desativado';
  criado_por: string;
  _expanded?: boolean;
  _cientes?: CienteRow[] | null;
}

interface TableState extends ListParams { page:number; limit:number; sort:string; direction:string; search:string; }

@Component({
  selector: 'app-admin-avisos-sala',
  standalone: true,
  imports: [FormsModule, RouterLink, PaginationComponent, ColumnFilterComponent, FmtDatePipe],
  template: `
    <h1>Inserir Avisos</h1>
    <a routerLink="/admin/operacao-audio" class="back-link">&larr; Voltar</a>

    <!-- ════════════ FORMULÁRIO DE CADASTRO ════════════ -->
    <section class="card-custom" style="max-width:720px; margin: 16px auto 24px;">
      <h2 style="margin-top:0">Novo Aviso</h2>

      <div class="form-row">
        <label>Local *</label>
        <select [(ngModel)]="salaId" name="sala_id">
          <option value="">Selecione...</option>
          @for (s of lookup.salas(); track s.id) {
            <option [value]="s.id">{{ s.nome }}</option>
          }
        </select>
      </div>

      <div class="form-row">
        <label>Mensagem *</label>
        <textarea [(ngModel)]="mensagem" name="mensagem" rows="4"></textarea>
      </div>

      <div class="form-row" style="max-width:260px">
        <label>Duração (dias) *</label>
        <input type="number" min="1" max="30" [(ngModel)]="duracaoDias" name="duracao_dias">
        <small class="text-muted-sm">Entre 1 e 30 dias.</small>
      </div>

      @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }

      <div style="display:flex; justify-content:flex-end; margin-top:12px">
        <button class="btn-primary-custom" [disabled]="saving()" (click)="onSubmit()">
          {{ saving() ? 'Salvando...' : 'Cadastrar Aviso' }}
        </button>
      </div>
    </section>

    <!-- ════════════ LISTAGEM ════════════ -->
    <section>
      <div class="section-header">
        <h2>Avisos Cadastrados</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="searchText" (input)="onSearchInput()" placeholder="Buscar no texto do aviso..." class="search-input search-wide">
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th style="width:30px"></th>
            <th>Aviso nº</th>
            <th>
              <app-column-filter [col]="cols[0]"
                [distinctValues]="gd(meta(), 'sala')"
                [currentSort]="state.sort" [currentDir]="state.direction"
                (sortChange)="onSortEvt($event)" (filterChange)="onFilterEvt($event)" />
            </th>
            <th>
              <app-column-filter [col]="cols[1]"
                [distinctValues]="gd(meta(), 'data')"
                [currentSort]="state.sort" [currentDir]="state.direction"
                (sortChange)="onSortEvt($event)" (filterChange)="onFilterEvt($event)" />
            </th>
            <th>Aviso</th>
            <th>
              <app-column-filter [col]="cols[2]"
                [distinctValues]="gd(meta(), 'expira')"
                [currentSort]="state.sort" [currentDir]="state.direction"
                (sortChange)="onSortEvt($event)" (filterChange)="onFilterEvt($event)" />
            </th>
            <th>
              <app-column-filter [col]="cols[3]"
                [distinctValues]="gd(meta(), 'status')"
                [currentSort]="state.sort" [currentDir]="state.direction"
                (sortChange)="onSortEvt($event)" (filterChange)="onFilterEvt($event)" />
            </th>
            <th>Ação</th>
          </tr></thead>
          <tbody>
            @if (rows().length === 0) {
              <tr><td colspan="8" class="empty-state">{{ loading() ? 'Carregando...' : 'Nenhum aviso cadastrado.' }}</td></tr>
            } @else {
              @for (a of rows(); track a.id) {
                <tr class="row-clickable" (click)="toggleExpand(a)">
                  <td><span class="btn-toggle">{{ a._expanded ? '▼' : '▶' }}</span></td>
                  <td>{{ a.numero }}</td>
                  <td>{{ a.sala_nome }}</td>
                  <td>{{ a.criado_em | fmtDate }}</td>
                  <td [title]="a.mensagem">{{ truncate(a.mensagem, 30) }}</td>
                  <td>{{ a.expira_em | fmtDate }}</td>
                  <td>
                    <span class="status-dot" [attr.data-status]="a.status"></span>
                    {{ a.status }}
                  </td>
                  <td>
                    @if (a.status === 'Ativo') {
                      <button class="btn-xs" (click)="desativar(a); $event.stopPropagation()">Desativar</button>
                    } @else { — }
                  </td>
                </tr>
                @if (a._expanded) {
                  <tr class="accordion-row">
                    <td colspan="8">
                      <strong class="accordion-title">Operadores que marcaram ciência:</strong>
                      @if (a._cientes === null) {
                        <p class="text-muted-sm">Carregando...</p>
                      } @else if (!a._cientes || a._cientes.length === 0) {
                        <p class="text-muted-sm">Nenhum operador marcou ciência neste aviso ainda.</p>
                      } @else {
                        <table class="sub-table">
                          <thead><tr><th>Operador</th></tr></thead>
                          <tbody>
                            @for (c of a._cientes; track c.operador_id) {
                              <tr><td>{{ c.operador_nome }}</td></tr>
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
      <app-pagination [meta]="meta()!"
                      (pageChange)="state.page = $event; load()"
                      (limitChange)="state.limit = $event; state.page = 1; load()" />
    </section>
  `,
  styles: [`
    section { margin-bottom: 28px; }
    .form-row { margin-bottom: 14px; }
    .form-row label { display:block; font-weight:500; font-size:.9375rem; margin-bottom:4px; }
    .form-row input, .form-row select, .form-row textarea { width:100%; }
    .text-muted-sm { color: var(--muted); font-size: .85rem; }
    .error-box { background:#fef2f2; color:#b91c1c; border:1px solid #fca5a5; border-radius:8px; padding:10px 14px; font-size:.875rem; margin-top:8px; }
    .status-dot { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:6px; vertical-align:middle; }
    .status-dot[data-status="Ativo"]      { background: var(--color-green, #16a34a); }
    .status-dot[data-status="Expirado"]   { background: #9ca3af; }
    .status-dot[data-status="Desativado"] { background: #111827; }
  `],
})
export class AdminAvisosSalaComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  lookup = inject(LookupService);

  // ── Form ──
  salaId = '';
  mensagem = '';
  duracaoDias: number | null = null;
  saving = signal(false);
  errorMsg = signal('');

  // ── Listagem ──
  cols: ColumnFilterDef[] = [
    { key: 'sala',   label: 'Local',     type: 'text' },
    { key: 'data',   label: 'Data',      type: 'date' },
    { key: 'expira', label: 'Expira em', type: 'date' },
    { key: 'status', label: 'Status',    type: 'text' },
  ];
  state: TableState = { page: 1, limit: 10, sort: 'data', direction: 'desc', search: '' };
  filters: Record<string, ColumnFilterState> = {};
  rows = signal<AvisoRow[]>([]);
  meta = signal<PaginationMeta | null>(null);
  loading = signal(true);
  searchText = '';
  private searchDebounce: any;

  ngOnInit(): void {
    if (this.lookup.salas().length === 0) this.lookup.loadSalas();
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.state.filters = buildFilters(this.filters);
    this.api.getList('/api/admin/avisos-sala/list', this.state).subscribe({
      next: r => {
        this.rows.set((r.data || []) as unknown as AvisoRow[]);
        this.meta.set(r.meta || null);
        this.loading.set(false);
      },
      error: () => { this.rows.set([]); this.loading.set(false); },
    });
  }

  onSearchInput(): void {
    clearTimeout(this.searchDebounce);
    this.searchDebounce = setTimeout(() => {
      this.state.search = this.searchText;
      this.state.page = 1;
      this.load();
    }, 400);
  }

  onSortEvt(e: { sort: string; direction: string }): void {
    this.state.sort = e.sort;
    this.state.direction = e.direction;
    this.state.page = 1;
    this.load();
  }

  onFilterEvt(e: { key: string; state: ColumnFilterState | null }): void {
    if (e.state) this.filters[e.key] = e.state;
    else delete this.filters[e.key];
    this.state.page = 1;
    this.load();
  }

  gd = getDistinct;

  onSubmit(): void {
    this.errorMsg.set('');
    if (!this.salaId) { this.errorMsg.set('Selecione o local.'); return; }
    if (!this.mensagem.trim()) { this.errorMsg.set('A mensagem é obrigatória.'); return; }
    if (!this.duracaoDias || this.duracaoDias < 1 || this.duracaoDias > 30) {
      this.errorMsg.set('A duração deve estar entre 1 e 30 dias.'); return;
    }

    this.saving.set(true);
    this.api.post<any>('/api/admin/avisos-sala', {
      sala_id: Number(this.salaId),
      mensagem: this.mensagem.trim(),
      duracao_dias: this.duracaoDias,
    }).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.ok) {
          this.toast.success('Aviso cadastrado com sucesso.');
          this.salaId = ''; this.mensagem = ''; this.duracaoDias = null;
          this.state.page = 1;
          this.load();
        } else {
          this.errorMsg.set(res.message || res.error || 'Erro ao cadastrar.');
        }
      },
      error: err => {
        this.saving.set(false);
        this.errorMsg.set(err?.error?.message || err?.error?.error || 'Erro ao cadastrar.');
      },
    });
  }

  toggleExpand(a: AvisoRow): void {
    a._expanded = !a._expanded;
    if (a._expanded && a._cientes === undefined) {
      // marca como "carregando" (null) e atualiza signal
      a._cientes = null;
      this.rows.set([...this.rows()]);
      this.api.get<any>(`/api/admin/avisos-sala/${a.id}/cientes`).subscribe({
        next: res => { a._cientes = (res?.data || []) as CienteRow[]; this.rows.set([...this.rows()]); },
        error: () => { a._cientes = []; this.rows.set([...this.rows()]); },
      });
    } else {
      this.rows.set([...this.rows()]);
    }
  }

  desativar(a: AvisoRow): void {
    if (!confirm(`Desativar o aviso nº ${a.numero} (${a.sala_nome})?`)) return;
    this.api.patch(`/api/admin/avisos-sala/${a.id}/desativar`, {}).subscribe({
      next: () => { this.toast.success('Aviso desativado.'); this.load(); },
      error: err => this.toast.error(err?.error?.message || 'Erro ao desativar.'),
    });
  }

  truncate(v: unknown, max: number): string {
    const s = String(v || '');
    return s.length > max ? s.substring(0, max) + '...' : s;
  }
}
