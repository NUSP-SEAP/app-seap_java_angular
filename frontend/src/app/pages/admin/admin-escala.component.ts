import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { LookupService, LookupItem } from '../../core/services/lookup.service';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { ToastService } from '../../shared/components/toast.component';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { PaginationMeta } from '../../core/models/user.model';

@Component({
  selector: 'app-admin-escala',
  standalone: true,
  imports: [FormsModule, RouterLink, FmtDatePipe, PaginationComponent],
  template: `
    <h1>Escala Semanal</h1>
    <a routerLink="/admin/operacao-audio" class="back-link">&larr; Voltar</a>

    <!-- ═══ ESCALAS EXISTENTES ═══ -->
    <section class="escalas-section">
      <h2>Escalas Cadastradas</h2>

      @if (loading()) {
        <p class="text-muted-sm">Carregando...</p>
      } @else if (escalas().length === 0) {
        <p class="text-muted-sm">Nenhuma escala cadastrada.</p>
      } @else {
        <div class="table-container">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width:30px"></th>
                <th>Período</th>
                <th>Criado por</th>
                <th>Criado em</th>
                <th style="width:160px">Ações</th>
              </tr>
            </thead>
            <tbody>
              @for (esc of escalas(); track esc['id']) {
                <tr>
                  <td><button class="btn-toggle" (click)="toggleDetalhe(esc)">{{ esc['_expanded'] ? '\u25BC' : '\u25B6' }}</button></td>
                  <td><strong>{{ esc['data_inicio'] | fmtDate }} — {{ esc['data_fim'] | fmtDate }}</strong></td>
                  <td>{{ esc['criado_por'] }}</td>
                  <td>{{ esc['criado_em'] | fmtDate }}</td>
                  <td>
                    <button class="btn-xs" (click)="editar(esc)">Editar</button>
                    <button class="btn-xs btn-xs-danger" (click)="excluir(esc)">Excluir</button>
                  </td>
                </tr>
                @if (esc['_expanded']) {
                  <tr class="accordion-row">
                    <td colspan="5">
                      @if (!esc['_resumo']) {
                        <p class="text-muted-sm">Carregando...</p>
                      } @else if (asArray(esc['_resumo']).length === 0) {
                        <p class="text-muted-sm">Nenhum operador escalado.</p>
                      } @else {
                        <table class="sub-table">
                          <thead><tr><th>Sala</th><th>Operadores</th></tr></thead>
                          <tbody>
                            @for (item of asArray(esc['_resumo']); track item['sala_nome']) {
                              <tr>
                                <td><strong>{{ item['sala_nome'] }}</strong></td>
                                <td>{{ item['operadores'] }}</td>
                              </tr>
                            }
                          </tbody>
                        </table>
                      }
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
        <app-pagination [meta]="escalasMeta()!"
          (pageChange)="escalasState.page = $event; loadEscalas()"
          (limitChange)="escalasState.limit = $event; escalasState.page = 1; loadEscalas()" />
      }
    </section>

    <!-- ═══ FORMULÁRIO ═══ -->
    <div class="card-custom form-card">
      <h2>{{ editandoId() ? 'Editar Escala' : 'Nova Escala' }}</h2>

      <div class="form-toolbar">
        <div class="form-row">
          <div class="form-group">
            <label>Data início</label>
            <input type="date" [(ngModel)]="dataInicio">
          </div>
          <div class="form-group">
            <label>Data fim</label>
            <input type="date" [(ngModel)]="dataFim">
          </div>
        </div>

        <div class="toolbar-actions">
          @if (!editandoId()) {
            <button class="btn-secondary-custom" (click)="gerarRodizio()"
              [disabled]="!dataInicio || !dataFim || salvando()"
              title="Rotaciona as vagas da última escala cadastrada conforme o ciclo 19→15→13→9→7→3→2→6">
              {{ salvando() ? 'Gerando...' : 'Gerar automaticamente' }}
            </button>
          }

          @if (editandoId()) {
            <button class="btn-cancelar" (click)="cancelarEdicao()">Cancelar Edição</button>
          }

          <button class="btn-primary-custom" (click)="salvar()"
            [disabled]="salvando() || !temAlgumaSelecao()"
            [title]="temAlgumaSelecao() ? '' : 'Selecione ao menos um operador para criar a escala'">
            {{ salvando() ? 'Salvando...' : (editandoId() ? 'Atualizar' : 'Criar Escala') }}
          </button>
        </div>
      </div>

      <div id="escala-salas-editor" class="salas-editor" tabindex="-1">
        @if (salaAtual(); as sala) {
          <div class="plenario-section">
            <div class="plenario-header">
              <strong>{{ sala.nome }}</strong>
              <div class="plenario-header-info">
                <span class="plenario-counter">{{ salaAtualIndex() + 1 }} de {{ salasNumeradas().length }}</span>
                <span class="badge-count">{{ getOperadoresSala(sala.id).length }} operador(es)</span>
              </div>
            </div>

            <div class="operadores-grid">
              @for (op of operadores(); track op.id) {
                <label class="op-checkbox" [class.checked]="isSelected(sala.id, op.id)">
                  <input type="checkbox"
                    [checked]="isSelected(sala.id, op.id)"
                    (change)="toggleOperador(sala.id, op.id)">
                  <span>{{ op.nome_completo || op.nome }}</span>
                  @if (getOutrosPlenarios(sala.id, op.id).length > 0) {
                    <span class="badge-outros">{{ getOutrosPlenarios(sala.id, op.id).join(', ') }}</span>
                  }
                </label>
              }
            </div>
          </div>

          <div class="plenario-nav">
            <button class="btn-cancelar" type="button" (click)="voltarPlenario()" [disabled]="salaAtualIndex() === 0">
              &lt;- Voltar
            </button>
            <button class="btn-cancelar" type="button" (click)="avancarPlenario()" [disabled]="salaAtualIndex() >= salasNumeradas().length - 1">
              Avançar -&gt;
            </button>
          </div>
        } @else {
          <p class="text-muted-sm">Nenhum plenário numerado encontrado.</p>
        }
      </div>

      <!-- ═══ FUNÇÕES (Apoio às Comissões / Fechamento dos Plenários) ═══ -->
      <div class="salas-editor funcoes-editor">
        <div class="plenario-section">
          <div class="plenario-header">
            <strong>{{ funcaoAtualLabel() }}</strong>
            <div class="plenario-header-info">
              <span class="plenario-counter">{{ funcaoAtualIndex() + 1 }} de {{ funcoesTipos.length }}</span>
              <span class="badge-count">{{ getOperadoresFuncao(funcaoAtualTipo()).length }} operador(es)</span>
            </div>
          </div>

          <div class="operadores-grid">
            @for (op of operadores(); track op.id) {
              <label class="op-checkbox" [class.checked]="isFuncaoSelected(funcaoAtualTipo(), op.id)">
                <input type="checkbox"
                  [checked]="isFuncaoSelected(funcaoAtualTipo(), op.id)"
                  (change)="toggleOperadorFuncao(funcaoAtualTipo(), op.id)">
                <span>{{ op.nome_completo || op.nome }}</span>
              </label>
            }
          </div>
        </div>

        <div class="plenario-nav">
          <button class="btn-cancelar" type="button" (click)="voltarFuncao()" [disabled]="funcaoAtualIndex() === 0">
            &lt;- Voltar
          </button>
          <button class="btn-cancelar" type="button" (click)="avancarFuncao()" [disabled]="funcaoAtualIndex() >= funcoesTipos.length - 1">
            Avançar -&gt;
          </button>
        </div>
      </div>

    </div>
  `,
  styles: [`
    .form-card { margin-bottom:24px; }
    .form-toolbar {
      display:flex; justify-content:space-between; align-items:flex-end; gap:16px;
      margin-bottom:20px; flex-wrap:wrap;
    }
    .form-row { display:flex; gap:16px; align-items:flex-end; flex-wrap:wrap; }
    .form-group { display:flex; flex-direction:column; gap:4px; }
    .form-group label { font-weight:600; font-size:.85rem; color:var(--muted); }
    .toolbar-actions { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }

    .plenario-section {
      border:1px solid var(--border); border-radius:var(--radius);
      padding:12px 16px; margin-bottom:12px;
    }
    .salas-editor:focus { outline:none; }
    .plenario-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; }
    .plenario-header-info { display:flex; gap:8px; align-items:center; flex-wrap:wrap; justify-content:flex-end; }
    .plenario-counter {
      font-size:.8rem; color:var(--muted);
    }
    .badge-count {
      font-size:.8rem; color:var(--muted); background:var(--table-header-bg);
      padding:2px 10px; border-radius:999px;
    }

    .operadores-grid {
      display:grid; grid-template-columns:repeat(auto-fill, minmax(200px, 1fr)); gap:4px 16px;
    }
    .op-checkbox {
      display:flex; align-items:center; gap:6px; padding:4px 8px;
      border-radius:4px; cursor:pointer; font-size:.9rem;
      transition:background .1s;
      &:hover { background:var(--row-hover); }
      &.checked { background:rgba(0,59,99,.08); font-weight:600; }
      input { width:16px; height:16px; cursor:pointer; }
    }
    .badge-outros {
      font-size:.7rem; color:var(--muted); opacity:.7;
      margin-left:auto; white-space:nowrap;
    }
    .plenario-nav {
      display:flex; justify-content:center; gap:12px; margin:4px 0 12px;
    }
    .funcoes-editor { margin-top:16px; }

    .escalas-section { margin-top:8px; }
    .btn-xs-danger {
      background:#dc2626 !important; color:#fff !important;
      border-color:#dc2626 !important;
      &:hover { background:#b91c1c !important; }
    }
    .btn-cancelar {
      background:var(--card); color:var(--text); border:1px solid var(--border);
      border-radius:999px; padding:8px 20px; font-weight:600; cursor:pointer;
      font-size:.9rem; transition:background .15s;
      &:hover { background:var(--row-hover); }
      &:disabled { opacity:.45; cursor:not-allowed; }
    }
    .btn-secondary-custom {
      background:#f2c94c; color:#003b63; border:1px solid #e0b52f;
      border-radius:999px; padding:8px 20px; font-weight:600; cursor:pointer;
      font-size:.9rem; transition:background .15s;
      &:hover:not(:disabled) { background:#e6bf3f; }
      &:disabled { opacity:.5; cursor:not-allowed; }
    }
    @media (max-width: 640px) {
      .form-toolbar { align-items:stretch; }
      .form-row, .toolbar-actions { width:100%; }
      .form-group { flex:1 1 140px; }
      .toolbar-actions > button { flex:1 1 180px; }
      .plenario-header { align-items:flex-start; gap:8px; }
    }
  `],
})
export class AdminEscalaComponent implements OnInit {
  private api = inject(ApiService);
  private lookup = inject(LookupService);
  private toast = inject(ToastService);

  // Formulário
  dataInicio = '';
  dataFim = '';
  editandoId = signal<number | null>(null);
  salvando = signal(false);

  // Seleção: Map<sala_id, Set<operador_id>> — signal para trigger change detection
  selecao = signal(new Map<number, Set<string>>());
  salaAtualIndex = signal(0);

  // Funções (Apoio às Comissões, Fechamento dos Plenários)
  readonly funcoesTipos = [
    { tipo: 'APOIO_COMISSOES', label: 'Apoio às Comissões' },
    { tipo: 'FECHAMENTO',      label: 'Fechamento dos Plenários' },
  ] as const;
  selecaoFuncao = signal(new Map<string, Set<string>>());
  funcaoAtualIndex = signal(0);

  // Dados
  escalas = signal<Record<string, any>[]>([]);
  escalasMeta = signal<PaginationMeta | null>(null);
  escalasState = { page: 1, limit: 10 };
  loading = signal(true);

  // Computed signals — reagem automaticamente ao lookup carregar
  salasNumeradas = computed(() =>
    this.lookup.salas().filter(s => /Plenário \d+/.test(s.nome))
  );
  salaAtual = computed(() => this.salasNumeradas()[this.salaAtualIndex()] || null);
  operadores = computed(() => this.lookup.operadores().filter(op => op.participa_escala === true));

  ngOnInit(): void {
    this.lookup.loadSalas();
    this.lookup.loadOperadores();
    this.loadEscalas();
  }

  loadEscalas(): void {
    this.loading.set(true);
    this.api.getList('/api/admin/escala/list', this.escalasState).subscribe({
      next: (res: any) => {
        this.escalas.set(res.data || []);
        this.escalasMeta.set(res.meta || null);
        this.loading.set(false);
      },
      error: () => { this.escalas.set([]); this.escalasMeta.set(null); this.loading.set(false); },
    });
  }

  // ── Seleção de operadores ──

  isSelected(salaId: number | string, opId: number | string): boolean {
    return this.selecao().get(Number(salaId))?.has(String(opId)) ?? false;
  }

  toggleOperador(salaId: number | string, opId: number | string): void {
    const map = this.selecao();
    const key = Number(salaId);
    if (!map.has(key)) map.set(key, new Set());
    const set = map.get(key)!;
    const id = String(opId);
    if (set.has(id)) set.delete(id); else set.add(id);
    this.selecao.set(new Map(map));
  }

  getOperadoresSala(salaId: number | string): string[] {
    return Array.from(this.selecao().get(Number(salaId)) || []);
  }

  voltarPlenario(): void {
    this.salaAtualIndex.update(idx => Math.max(0, idx - 1));
  }

  avancarPlenario(): void {
    this.salaAtualIndex.update(idx => Math.min(this.salasNumeradas().length - 1, idx + 1));
  }

  // ── Funções (Apoio / Fechamento) ──

  funcaoAtualTipo(): string { return this.funcoesTipos[this.funcaoAtualIndex()].tipo; }
  funcaoAtualLabel(): string { return this.funcoesTipos[this.funcaoAtualIndex()].label; }

  isFuncaoSelected(tipo: string, opId: number | string): boolean {
    return this.selecaoFuncao().get(tipo)?.has(String(opId)) ?? false;
  }

  toggleOperadorFuncao(tipo: string, opId: number | string): void {
    const map = this.selecaoFuncao();
    if (!map.has(tipo)) map.set(tipo, new Set());
    const set = map.get(tipo)!;
    const id = String(opId);
    if (set.has(id)) set.delete(id); else set.add(id);
    this.selecaoFuncao.set(new Map(map));
  }

  getOperadoresFuncao(tipo: string): string[] {
    return Array.from(this.selecaoFuncao().get(tipo) || []);
  }

  voltarFuncao(): void {
    this.funcaoAtualIndex.update(idx => Math.max(0, idx - 1));
  }

  avancarFuncao(): void {
    this.funcaoAtualIndex.update(idx => Math.min(this.funcoesTipos.length - 1, idx + 1));
  }

  /** True quando há pelo menos 1 operador selecionado em qualquer sala ou função. */
  temAlgumaSelecao = computed(() => {
    for (const ops of this.selecao().values()) if (ops.size > 0) return true;
    for (const ops of this.selecaoFuncao().values()) if (ops.size > 0) return true;
    return false;
  });

  /** Retorna nomes abreviados dos outros plenários em que o operador já está selecionado */
  getOutrosPlenarios(salaIdAtual: number | string, opId: number | string): string[] {
    const id = String(opId);
    const result: string[] = [];
    for (const [salaId, ops] of this.selecao().entries()) {
      if (salaId === Number(salaIdAtual)) continue;
      if (!ops.has(id)) continue;
      const sala = this.salasNumeradas().find(s => Number(s.id) === salaId);
      if (sala) {
        const num = sala.nome.match(/\d+/);
        result.push(num ? `P${num[0]}` : sala.nome);
      }
    }
    return result;
  }

  // ── CRUD ──

  salvar(): void {
    if (!this.dataInicio || !this.dataFim) {
      alert('Preencha as datas de início e fim.');
      return;
    }

    this.salvando.set(true);

    // Montar payload
    const salas: Record<string, string[]> = {};
    for (const [salaId, ops] of this.selecao().entries()) {
      if (ops.size > 0) {
        salas[String(salaId)] = Array.from(ops);
      }
    }

    const funcoes: Record<string, string[]> = {};
    for (const [tipo, ops] of this.selecaoFuncao().entries()) {
      if (ops.size > 0) {
        funcoes[tipo] = Array.from(ops);
      }
    }

    const payload: any = {
      data_inicio: this.dataInicio,
      data_fim: this.dataFim,
      salas,
      funcoes,
    };
    if (this.editandoId()) payload.id = this.editandoId();

    const eraEdicao = this.editandoId() != null;
    this.api.post<any>('/api/admin/escala/save', payload).subscribe({
      next: () => {
        this.salvando.set(false);
        this.cancelarEdicao();
        this.loadEscalas();
        this.toast.success(eraEdicao ? 'Escala atualizada com sucesso' : 'Escala criada com sucesso');
      },
      error: (err: any) => {
        this.salvando.set(false);
        this.toast.error(err?.error?.message || 'Erro ao salvar escala.');
      },
    });
  }

  gerarRodizio(): void {
    if (!this.dataInicio || !this.dataFim) return;
    this.salvando.set(true);
    this.api.post<any>('/api/admin/escala/rodizio/preview', {
      data_inicio: this.dataInicio, data_fim: this.dataFim,
    }).subscribe({
      next: (res: any) => {
        this.salvando.set(false);
        const novaSelecao = new Map<number, Set<string>>();
        const salas = res.data?.salas || {};
        for (const [salaId, ops] of Object.entries(salas)) {
          novaSelecao.set(Number(salaId), new Set((ops as unknown[]).map(String)));
        }
        this.selecao.set(novaSelecao);
        this.salaAtualIndex.set(0);
        this.focarAreaSalas();
        this.toast.success('Prévia gerada. Revise e clique em Criar Escala para salvar.');
      },
      error: (err: any) => {
        this.salvando.set(false);
        this.toast.error(err?.error?.message || 'Erro ao gerar prévia da escala automática.');
      },
    });
  }

  editar(esc: Record<string, any>): void {
    this.editandoId.set(esc['id']);
    this.dataInicio = esc['data_inicio'];
    this.dataFim = esc['data_fim'];

    // Carregar operadores da escala
    this.api.get<any>(`/api/admin/escala/${esc['id']}`).subscribe({
      next: (res: any) => {
        const data = res.data || {};
        const novaSelecao = new Map<number, Set<string>>();
        const salas = data['salas'] || {};
        for (const [salaId, ops] of Object.entries(salas)) {
          novaSelecao.set(Number(salaId), new Set(ops as string[]));
        }
        this.selecao.set(novaSelecao);

        const novaFuncao = new Map<string, Set<string>>();
        const funcoes = data['funcoes'] || {};
        for (const [tipo, ops] of Object.entries(funcoes)) {
          novaFuncao.set(tipo, new Set(ops as string[]));
        }
        this.selecaoFuncao.set(novaFuncao);
      },
    });

    this.focarAreaSalas();
  }

  private focarAreaSalas(): void {
    window.setTimeout(() => {
      const editor = document.getElementById('escala-salas-editor');
      if (!editor) return;
      editor.scrollIntoView({ behavior: 'smooth', block: 'start' });
      editor.focus({ preventScroll: true });
    }, 0);
  }

  excluir(esc: Record<string, any>): void {
    const periodo = `${esc['data_inicio']} a ${esc['data_fim']}`;
    if (!confirm(`Excluir a escala de ${periodo}?`)) return;

    this.api.delete<any>(`/api/admin/escala/${esc['id']}`).subscribe({
      next: () => {
        if (this.editandoId() === esc['id']) this.cancelarEdicao();
        this.loadEscalas();
      },
      error: () => alert('Erro ao excluir escala.'),
    });
  }

  cancelarEdicao(): void {
    this.editandoId.set(null);
    this.dataInicio = '';
    this.dataFim = '';
    this.selecao.set(new Map());
    this.selecaoFuncao.set(new Map());
    this.salaAtualIndex.set(0);
    this.funcaoAtualIndex.set(0);
  }

  toggleDetalhe(esc: Record<string, any>): void {
    esc['_expanded'] = !esc['_expanded'];
    if (esc['_expanded'] && !esc['_resumo']) {
      this.api.get<any>(`/api/admin/escala/${esc['id']}`).subscribe({
        next: (res: any) => {
          esc['_resumo'] = res.data?.resumo || [];
          this.escalas.set([...this.escalas()]);
        },
        error: () => { esc['_resumo'] = []; this.escalas.set([...this.escalas()]); },
      });
    }
  }

  asArray(v: unknown): any[] { return Array.isArray(v) ? v : []; }
}
