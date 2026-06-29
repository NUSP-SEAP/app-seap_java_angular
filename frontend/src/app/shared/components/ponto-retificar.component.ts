import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { FmtDatePipe } from '../pipes/fmt-date.pipe';

interface LinhaPonto {
  dia: string;
  ent1: string; sai1: string; ent2: string; sai2: string;
  total_dia: string; banco: string;
  // estado local da retificação
  aberto?: boolean;
  conteudo?: string;
}

interface DadosFolha {
  id: string;
  tipo: string;
  data_inicio: string;
  data_fim: string;
  linhas: LinhaPonto[];
}

/**
 * Retificação de ponto: mostra a folha selecionada como tabela (7 colunas
 * espelhando o Secullum) e permite abrir, por dia, uma área para descrever a
 * retificação. As áreas aparecem em ordem cronológica (a da tabela), não na
 * ordem de clique. "Salvar" apenas confirma e volta — persistência fica para
 * a etapa de banco de dados. Compartilhada por operador, técnico e admin.
 */
@Component({
  selector: 'app-ponto-retificar',
  standalone: true,
  imports: [FormsModule, RouterLink, FmtDatePipe],
  template: `
    <h1>Retificação de Ponto</h1>
    <div class="topo-bar">
      <a [routerLink]="voltarLink()" class="back-link">&larr; Voltar</a>
      @if (selecionadas().length > 0 && !enviado()) {
        <button class="btn-primary-custom salvar-top" (click)="salvar()">Salvar</button>
      }
    </div>

    @if (enviado()) {
      <div class="ok-box">Retificação Enviada</div>
    }

    @if (loading()) {
      <p class="text-muted-sm">Carregando folha...</p>
    } @else if (erro()) {
      <div class="error-box">{{ erro() }}</div>
    } @else {
      <p class="text-muted-sm periodo">
        Folha {{ tipoLabel() }} — {{ dados()!.data_inicio | fmtDate }} a {{ dados()!.data_fim | fmtDate }}
      </p>

      <!-- Desktop: tabela (inalterada) -->
      <div class="table-container vista-desktop">
        <table class="data-table ponto-table">
          <thead><tr>
            <th>DIA</th>
            <th>ENT. 1</th><th>SAÍ. 1</th><th>ENT. 2</th><th>SAÍ. 2</th>
            <th>TOTALDIA</th><th>BANCO</th>
            <th style="width:84px; text-align:center">Retificar</th>
          </tr></thead>
          <tbody>
            @for (l of linhas(); track l.dia) {
              <tr [class.row-sel]="l.aberto">
                <td><strong>{{ l.dia }}</strong></td>
                <td>{{ l.ent1 }}</td><td>{{ l.sai1 }}</td>
                <td>{{ l.ent2 }}</td><td>{{ l.sai2 }}</td>
                <td>{{ l.total_dia }}</td><td>{{ l.banco }}</td>
                <td style="text-align:center">
                  <button class="btn-pm" [class.on]="l.aberto" (click)="toggle(l)"
                          [attr.aria-label]="l.aberto ? 'Remover retificação' : 'Retificar este dia'">
                    {{ l.aberto ? '−' : '+' }}
                  </button>
                </td>
              </tr>
              @if (l.aberto) {
                <tr class="accordion-row">
                  <td colspan="8">
                    <div class="retif-area">
                      <label>Conteúdo da Retificação</label>
                      <textarea [(ngModel)]="l.conteudo" rows="3"></textarea>
                    </div>
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>

      <!-- Mobile: um card por dia (mesmo padrão do "Registro manual de ponto") -->
      <div class="vista-mobile">
        @for (l of linhas(); track l.dia) {
          <div class="dia-card" [class.sel]="l.aberto">
            <div class="col-dia">
              <strong>{{ l.dia }}</strong>
              <button class="btn-pm" [class.on]="l.aberto" (click)="toggle(l)"
                      [attr.aria-label]="l.aberto ? 'Remover retificação' : 'Retificar este dia'">
                {{ l.aberto ? '−' : '+' }}
              </button>
            </div>

            @if (isStatus(l)) {
              <div class="status-cell">{{ l.ent1 }}</div>
            } @else {
              <div class="cel c-ent1"><span class="lbl">Ent. 1</span><span class="val">{{ l.ent1 || '—' }}</span></div>
              <div class="cel c-sai1"><span class="lbl">Saí. 1</span><span class="val">{{ l.sai1 || '—' }}</span></div>
              <div class="cel c-ent2"><span class="lbl">Ent. 2</span><span class="val">{{ l.ent2 || '—' }}</span></div>
              <div class="cel c-sai2"><span class="lbl">Saí. 2</span><span class="val">{{ l.sai2 || '—' }}</span></div>
            }

            <div class="resumo total"><span class="lbl">Total dia</span><strong>{{ l.total_dia || '—' }}</strong></div>
            <div class="resumo banco"><span class="lbl">Banco</span><strong>{{ l.banco || '—' }}</strong></div>
          </div>
          @if (l.aberto) {
            <div class="retif-area retif-area-mobile">
              <label>Conteúdo da Retificação</label>
              <textarea [(ngModel)]="l.conteudo" rows="3"></textarea>
            </div>
          }
        }
      </div>
    }
  `,
  styles: [`
    .periodo { margin: 0 0 16px; }
    .ponto-table td { font-variant-numeric: tabular-nums; }
    .row-sel td { background: #eff6ff; }
    .btn-pm {
      width: 30px; height: 30px; line-height: 1; font-size: 1.1rem; font-weight: 700;
      border: 1px solid var(--border); border-radius: 6px; background: #fff; color: var(--text);
      cursor: pointer; padding: 0;
    }
    .btn-pm:hover { background: var(--row-hover); }
    .btn-pm.on { border-color: var(--primary); color: var(--primary); }

    /* Topo: Voltar à esquerda, Salvar à direita (na mesma linha) */
    .topo-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
    .topo-bar .back-link { margin-bottom: 0; }
    .salvar-top { margin-left: auto; }

    /* Área de retificação inline (abaixo do dia, no desktop e no celular) */
    .retif-area { margin: 0; }
    .retif-area label { display: block; font-weight: 600; font-size: .9375rem; margin-bottom: 6px; }
    .retif-area textarea { width: 100%; resize: vertical; box-sizing: border-box; }
    .retif-area-mobile { padding: 0 2px 4px; }

    .error-box {
      background: #fef2f2; color: #b91c1c; border: 1px solid #fca5a5; border-radius: 8px;
      padding: 10px 14px; font-size: .875rem;
    }
    .ok-box {
      margin-top: 16px; background: #ecfdf5; color: #047857; border: 1px solid #6ee7b7;
      border-radius: 8px; padding: 12px 16px; font-weight: 600;
    }

    /* ───── Responsivo: tabela no desktop, cards por dia no celular ───── */
    .vista-mobile { display: none; }
    @media (max-width: 640px) {
      .vista-desktop { display: none; }
      .vista-mobile { display: flex; flex-direction: column; gap: 8px; }

      .dia-card {
        display: grid;
        grid-template-columns: minmax(58px, .8fr) 1fr 1fr 1fr 1fr;
        grid-template-rows: auto auto;
        gap: 6px;
        border: 1px solid var(--border); border-radius: 10px; padding: 8px;
      }
      .dia-card.sel { background: #eff6ff; border-color: var(--primary); }
      .col-dia {
        grid-column: 1; grid-row: 1 / 3;
        display: flex; flex-direction: column; align-items: flex-start; justify-content: center; gap: 8px;
      }
      .col-dia strong { font-size: .8rem; color: var(--primary); line-height: 1.15; word-break: break-word; }

      .cel { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
      .cel .lbl { font-size: .6rem; font-weight: 600; color: #64748b; }
      .cel .val { font-size: .82rem; font-variant-numeric: tabular-nums; }
      .c-ent1 { grid-column: 2; grid-row: 1; }
      .c-sai1 { grid-column: 3; grid-row: 1; }
      .c-ent2 { grid-column: 4; grid-row: 1; }
      .c-sai2 { grid-column: 5; grid-row: 1; }
      .status-cell {
        grid-column: 2 / 6; grid-row: 1; align-self: center;
        font-size: .85rem; font-weight: 600; color: var(--text);
      }
      .resumo {
        display: flex; align-items: center; justify-content: space-between; gap: 6px;
        background: #f1f5f9; border-radius: 6px; padding: 4px 8px; font-size: .76rem;
      }
      .resumo .lbl { color: #475569; }
      .resumo strong { font-variant-numeric: tabular-nums; color: var(--text); }
      .total { grid-column: 2 / 4; grid-row: 2; }
      .banco { grid-column: 4 / 6; grid-row: 2; }
    }
  `],
})
export class PontoRetificarComponent {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private auth = inject(AuthService);

  dados = signal<DadosFolha | null>(null);
  linhas = signal<LinhaPonto[]>([]);
  loading = signal(true);
  erro = signal('');
  enviado = signal(false);

  /** Linhas com área aberta — filtradas da lista, então já em ordem cronológica. */
  selecionadas = computed(() => this.linhas().filter(l => l.aberto));
  voltarLink = computed(() => this.auth.role() === 'administrador' ? '/admin/ponto' : '/ponto');

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('paginaId');
    if (!id) { this.erro.set('Folha não informada.'); this.loading.set(false); return; }
    this.api.get<any>(`/api/ponto/folha/${id}/dados`).subscribe({
      next: res => {
        const d: DadosFolha = res.data;
        this.dados.set(d);
        this.linhas.set((d.linhas || []).map(l => ({ ...l, aberto: false, conteudo: '' })));
        this.loading.set(false);
      },
      error: err => {
        this.erro.set(err?.error?.error || err?.error?.message || 'Não foi possível carregar a folha.');
        this.loading.set(false);
      },
    });
  }

  tipoLabel(): string { return this.dados()?.tipo === 'MENSAL' ? 'mensal' : 'semanal'; }

  /** Dia de status (Feriado/Falta/DISPOSI/…) — tem letras nas células, não horas. */
  isStatus(l: LinhaPonto): boolean {
    return /[A-Za-zÀ-ÿ]/.test(l.ent1 || '');
  }

  /** "+" abre a área do dia; "−" remove (exclui o conteúdo digitado). */
  toggle(l: LinhaPonto): void {
    l.aberto = !l.aberto;
    if (!l.aberto) l.conteudo = '';
    this.linhas.set([...this.linhas()]);
  }

  salvar(): void {
    // Persistência fica para a etapa de banco de dados (Liquibase único no final).
    this.enviado.set(true);
    setTimeout(() => this.router.navigateByUrl(this.voltarLink()), 1400);
  }
}
