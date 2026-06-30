import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService, ListParams } from '../../core/services/api.service';
import { LookupService } from '../../core/services/lookup.service';
import { PaginationMeta } from '../../core/models/user.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef, ColumnFilterState } from '../../shared/components/column-filter.component';
import { MultiSelectDropdownComponent, MultiSelectOption } from '../../shared/components/multi-select-dropdown.component';
import { getDistinct, buildFilters } from '../../core/helpers/table.helpers';
import { ToastService } from '../../shared/components/toast.component';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';

interface AvisoRow {
  id: string;
  numero: number;
  tipo: string;        // já vem como label ("Verificação")
  criado_em: string;
  criado_por: string;
  expira_em: string | null;
  status: 'Ativo' | 'Expirado' | 'Desativado';
  permanente: number;  // 0/1
}

interface TableState extends ListParams { page:number; limit:number; sort:string; direction:string; search:string; }

@Component({
  selector: 'app-admin-avisos-sala',
  standalone: true,
  imports: [FormsModule, RouterLink, PaginationComponent, ColumnFilterComponent, MultiSelectDropdownComponent, FmtDatePipe],
  template: `
    <h1>Inserir Avisos</h1>
    <a routerLink="/admin/gestao-pessoas" class="back-link">&larr; Voltar</a>

    <!-- ════════════ FORMULÁRIO DE CADASTRO ════════════ -->
    <section class="card-custom" style="max-width:720px; margin: 16px auto 24px;">
      <div class="form-row">
        <label>Local <span class="req">*</span></label>
        <app-multi-select-dropdown
          [options]="salaOptions()"
          [selected]="selectedSalaIds"
          [lockedIds]="lockedSalaIds()"
          placeholder="Selecione um ou mais locais..."
          (selectionChange)="selectedSalaIds = $event" />
      </div>

      @for (msg of mensagens; track $index) {
        <div class="form-row">
          <label>{{ $index + 1 }}º Aviso <span class="req">*</span></label>
          <textarea [(ngModel)]="mensagens[$index]" [name]="'msg_' + $index" rows="2"></textarea>
        </div>
      }

      <div class="msg-actions">
        @if (mensagens.length < MAX_MENSAGENS) {
          <button type="button" class="btn-outline" (click)="addMensagem()">+ Novo Aviso</button>
        }
        @if (mensagens.length > 1) {
          <button type="button" class="btn-outline" (click)="removerUltimaMensagem()">Remover</button>
        }
      </div>

      <div class="form-row" style="margin-top:14px">
        <label>Aviso permanente <span class="req">*</span></label>
        <div class="radio-row">
          <label class="radio-opt"><input type="radio" [(ngModel)]="permanente" name="permanente" [value]="true"> Sim</label>
          <label class="radio-opt"><input type="radio" [(ngModel)]="permanente" name="permanente" [value]="false"> Não</label>
          @if (!permanente) {
            <span class="duracao-inline">
              <label>Duração (dias) <span class="req">*</span></label>
              <input type="number" min="1" max="30" [(ngModel)]="duracaoDias" name="duracao_dias">
            </span>
          }
        </div>
      </div>

      <div class="form-row">
        <label class="check-opt">
          <input type="checkbox" [(ngModel)]="manterAposCiencia" name="manter">
          Manter aviso após ciência do operador
        </label>
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
          <input type="text" [(ngModel)]="searchText" (input)="onSearchInput()" placeholder="Buscar por autor ou nº do cadastro..." class="search-input search-wide">
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th>Cadastro nº</th>
            <th>
              <app-column-filter [col]="cols[0]"
                [distinctValues]="gd(meta(), 'tipo')"
                [currentSort]="state.sort" [currentDir]="state.direction"
                (sortChange)="onSortEvt($event)" (filterChange)="onFilterEvt($event)" />
            </th>
            <th>
              <app-column-filter [col]="cols[1]"
                [distinctValues]="gd(meta(), 'data')"
                [currentSort]="state.sort" [currentDir]="state.direction"
                (sortChange)="onSortEvt($event)" (filterChange)="onFilterEvt($event)" />
            </th>
            <th>
              <app-column-filter [col]="cols[2]"
                [distinctValues]="gd(meta(), 'criado_por')"
                [currentSort]="state.sort" [currentDir]="state.direction"
                (sortChange)="onSortEvt($event)" (filterChange)="onFilterEvt($event)" />
            </th>
            <th>
              <app-column-filter [col]="cols[3]"
                [distinctValues]="gd(meta(), 'expira')"
                [currentSort]="state.sort" [currentDir]="state.direction"
                (sortChange)="onSortEvt($event)" (filterChange)="onFilterEvt($event)" />
            </th>
            <th>
              <app-column-filter [col]="cols[4]"
                [distinctValues]="gd(meta(), 'status')"
                [currentSort]="state.sort" [currentDir]="state.direction"
                (sortChange)="onSortEvt($event)" (filterChange)="onFilterEvt($event)" />
            </th>
            <th>Ação</th>
          </tr></thead>
          <tbody>
            @if (rows().length === 0) {
              <tr><td colspan="7" class="empty-state">{{ loading() ? 'Carregando...' : 'Nenhum aviso cadastrado.' }}</td></tr>
            } @else {
              @for (a of rows(); track a.id) {
                <tr class="row-clickable" (dblclick)="abrirDetalhe(a)" title="Duplo-clique para ver o detalhe">
                  <td>{{ a.numero }}</td>
                  <td>{{ a.tipo }}</td>
                  <td>{{ a.criado_em | fmtDate }}</td>
                  <td>{{ a.criado_por }}</td>
                  <td>{{ a.permanente ? '—' : (a.expira_em | fmtDate) }}</td>
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
    .form-row input[type="number"], .form-row select, .form-row textarea { width:100%; }
    .form-row textarea { resize: vertical; }
    .req { color:#dc2626; }
    .text-muted-sm { color: var(--muted); font-size: .85rem; }
    .msg-actions { display:flex; gap:8px; margin-bottom:4px; }
    .btn-outline {
      background: #fff; color: var(--primary); border: 1px solid var(--primary);
      border-radius: 999px; padding: 8px 16px; font-weight: 600; cursor: pointer; font-size: .85rem;
      &:hover { background: #eff6ff; }
    }
    .radio-row { display:flex; align-items:center; gap:18px; flex-wrap:wrap; }
    .radio-opt { display:flex; align-items:center; gap:6px; font-weight:500; margin:0; cursor:pointer; }
    .radio-opt input { width:auto; }
    .duracao-inline { display:flex; align-items:center; gap:8px; }
    .duracao-inline label { margin:0; white-space:nowrap; }
    .duracao-inline input { width:90px; }
    .check-opt { display:flex; align-items:center; gap:8px; font-weight:500; cursor:pointer; }
    .check-opt input { width:auto; }
    .row-clickable { cursor: pointer; }
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
  private router = inject(Router);
  lookup = inject(LookupService);

  readonly MAX_MENSAGENS = 10;

  // ── Form ──
  // Tipo travado em VERIFICACAO nesta versão (enviado literal no payload).
  // A escolha de tipo terá outra UI numa próxima entrega.
  selectedSalaIds: string[] = [];
  mensagens: string[] = [''];
  permanente = true;
  duracaoDias: number | null = null;
  manterAposCiencia = false;
  saving = signal(false);
  errorMsg = signal('');
  // sala_id (string) → nº do cadastro ativo que a ocupa (Fix: 1 aviso ativo por sala)
  salasOcupadas = signal<Record<string, number>>({});

  // ── Listagem ──
  cols: ColumnFilterDef[] = [
    { key: 'tipo',       label: 'Tipo de Aviso',  type: 'text' },
    { key: 'data',       label: 'Data',           type: 'date' },
    { key: 'criado_por', label: 'Cadastrado por', type: 'text' },
    { key: 'expira',     label: 'Expira em',      type: 'date' },
    { key: 'status',     label: 'Status',         type: 'text' },
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
    this.loadSalasOcupadas();
    this.load();
  }

  loadSalasOcupadas(): void {
    this.api.get<any>('/api/admin/avisos/salas-ocupadas').subscribe({
      next: res => {
        const map: Record<string, number> = {};
        (res?.data || []).forEach((r: any) => { map[String(r.sala_id)] = r.numero; });
        this.salasOcupadas.set(map);
      },
    });
  }

  /** Salas com aviso ativo ganham "— Cadastro nº X" no rótulo e ficam desabilitadas. */
  salaOptions(): MultiSelectOption[] {
    const ocup = this.salasOcupadas();
    return this.lookup.salas().map(s => {
      const id = String(s.id);
      const num = ocup[id];
      return num != null
        ? { id, label: `${s.nome} — Cadastro nº ${num}` }
        : { id, label: s.nome };
    });
  }

  lockedSalaIds(): string[] {
    return Object.keys(this.salasOcupadas());
  }

  addMensagem(): void {
    if (this.mensagens.length < this.MAX_MENSAGENS) this.mensagens.push('');
  }

  removerUltimaMensagem(): void {
    if (this.mensagens.length > 1) this.mensagens.pop();
  }

  load(): void {
    this.loading.set(true);
    this.state.filters = buildFilters(this.filters);
    this.api.getList('/api/admin/avisos/list', this.state).subscribe({
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
    if (this.selectedSalaIds.length === 0) { this.errorMsg.set('Selecione ao menos um local.'); return; }
    const msgs = this.mensagens.map(m => m.trim());
    if (msgs.some(m => !m)) { this.errorMsg.set('Preencha todas as mensagens.'); return; }
    if (!this.permanente && (!this.duracaoDias || this.duracaoDias < 1 || this.duracaoDias > 30)) {
      this.errorMsg.set('A duração deve estar entre 1 e 30 dias.'); return;
    }

    this.saving.set(true);
    this.api.post<any>('/api/admin/avisos', {
      tipo: 'VERIFICACAO',
      permanente: this.permanente,
      duracao_dias: this.permanente ? null : this.duracaoDias,
      manter_apos_ciencia: this.manterAposCiencia,
      mensagens: msgs,
      alvo_tipo: 'SALA',
      sala_ids: this.selectedSalaIds.map(Number),
      operador_ids: [],
      tecnico_ids: [],
    }).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.ok) {
          this.toast.success('Aviso cadastrado com sucesso.');
          this.resetForm();
          this.loadSalasOcupadas();
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

  resetForm(): void {
    this.selectedSalaIds = [];
    this.mensagens = [''];
    this.permanente = true;
    this.duracaoDias = null;
    this.manterAposCiencia = false;
  }

  abrirDetalhe(a: AvisoRow): void {
    this.router.navigate(['/admin/aviso/detalhe'], { queryParams: { id: a.id } });
  }

  desativar(a: AvisoRow): void {
    if (!confirm(`Desativar o cadastro nº ${a.numero}?`)) return;
    this.api.patch(`/api/admin/avisos/${a.id}/desativar`, {}).subscribe({
      next: () => { this.toast.success('Aviso desativado.'); this.load(); },
      error: err => this.toast.error(err?.error?.message || 'Erro ao desativar.'),
    });
  }
}
