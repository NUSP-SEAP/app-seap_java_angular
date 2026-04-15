import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { LookupService, LookupItem } from '../../core/services/lookup.service';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';

@Component({
  selector: 'app-admin-escala',
  standalone: true,
  imports: [FormsModule, RouterLink, FmtDatePipe],
  template: `
    <h1>Escala Semanal</h1>

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
      }
    </section>

    <!-- ═══ FORMULÁRIO ═══ -->
    <div class="card-custom form-card">
      <h2>{{ editandoId() ? 'Editar Escala' : 'Nova Escala' }}</h2>

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

      <!-- Plenários numerados -->
      @for (sala of salasNumeradas(); track sala.id) {
        <div class="plenario-section">
          <div class="plenario-header">
            <strong>{{ sala.nome }}</strong>
            <span class="badge-count">{{ getOperadoresSala(sala.id).length }} operador(es)</span>
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
      }

      <!-- Botões no final do formulário -->
      <div class="form-footer">
        <a routerLink="/admin" class="btn-cancelar">Voltar</a>
        @if (editandoId()) {
          <button class="btn-cancelar" (click)="cancelarEdicao()">Cancelar Edição</button>
        }
        <button class="btn-primary-custom" (click)="salvar()" [disabled]="salvando()">
          {{ salvando() ? 'Salvando...' : (editandoId() ? 'Atualizar' : 'Criar Escala') }}
        </button>
      </div>
    </div>
  `,
  styles: [`
    .form-card { margin-bottom:24px; }
    .form-row { display:flex; gap:16px; align-items:flex-end; margin-bottom:20px; flex-wrap:wrap; }
    .form-group { display:flex; flex-direction:column; gap:4px; }
    .form-group label { font-weight:600; font-size:.85rem; color:var(--muted); }
    .form-footer {
      display:flex; gap:10px; justify-content:flex-end; margin-top:20px;
      padding-top:16px; border-top:1px solid var(--border);
    }

    .plenario-section {
      border:1px solid var(--border); border-radius:var(--radius);
      padding:12px 16px; margin-bottom:12px;
    }
    .plenario-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; }
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
    }
  `],
})
export class AdminEscalaComponent implements OnInit {
  private api = inject(ApiService);
  private lookup = inject(LookupService);

  // Formulário
  dataInicio = '';
  dataFim = '';
  editandoId = signal<number | null>(null);
  salvando = signal(false);

  // Seleção: Map<sala_id, Set<operador_id>> — signal para trigger change detection
  selecao = signal(new Map<number, Set<string>>());

  // Dados
  escalas = signal<Record<string, any>[]>([]);
  loading = signal(true);

  // Computed signals — reagem automaticamente ao lookup carregar
  salasNumeradas = computed(() =>
    this.lookup.salas().filter(s => /Plenário \d+/.test(s.nome))
  );
  operadores = computed(() => this.lookup.operadores());

  ngOnInit(): void {
    this.lookup.loadSalas();
    this.lookup.loadOperadores();
    this.loadEscalas();
  }

  loadEscalas(): void {
    this.loading.set(true);
    this.api.get<any>('/api/admin/escala/list').subscribe({
      next: (res: any) => {
        this.escalas.set(res.data || []);
        this.loading.set(false);
      },
      error: () => { this.escalas.set([]); this.loading.set(false); },
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

    const payload: any = {
      data_inicio: this.dataInicio,
      data_fim: this.dataFim,
      salas,
    };
    if (this.editandoId()) payload.id = this.editandoId();

    this.api.post<any>('/api/admin/escala/save', payload).subscribe({
      next: () => {
        this.salvando.set(false);
        this.cancelarEdicao();
        this.loadEscalas();
      },
      error: (err: any) => {
        this.salvando.set(false);
        alert(err?.error?.message || 'Erro ao salvar escala.');
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
      },
    });

    // Scroll to top
    window.scrollTo({ top: 0, behavior: 'smooth' });
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
