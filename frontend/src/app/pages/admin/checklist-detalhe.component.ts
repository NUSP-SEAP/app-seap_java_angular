import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FmtTimePipe } from '../../shared/pipes/fmt-time.pipe';

@Component({
  selector: 'app-checklist-detalhe',
  standalone: true,
  imports: [FmtDatePipe, FmtTimePipe],
  template: `
    <div class="card-custom detalhe-card">
      @if (loading()) {
        <p class="text-muted-sm">Carregando...</p>
      } @else if (!data()) {
        <p class="text-muted-sm">Checklist não encontrado.</p>
      } @else {
        <div class="detalhe-header">
          <h1>Detalhe da Verificação de Plenário</h1>
          <span class="badge-readonly">APENAS LEITURA</span>
        </div>

        <!-- 1) Identificação -->
        <h3>1) Identificação</h3>
        <div class="field-row two-cols">
          <div class="field">
            <label>Data</label>
            <div class="field-value">{{ data()!['data_operacao'] | fmtDate }}</div>
          </div>
          <div class="field">
            <label>Local</label>
            <div class="field-value">{{ data()!['sala_nome'] }}</div>
          </div>
        </div>
        <div class="field-row three-cols">
          <div class="field">
            <label>Início</label>
            <div class="field-value">{{ data()!['hora_inicio_testes'] | fmtTime }}</div>
          </div>
          <div class="field">
            <label>Término</label>
            <div class="field-value">{{ data()!['hora_termino_testes'] | fmtTime }}</div>
          </div>
          <div class="field">
            <label>Duração</label>
            <div class="field-value">{{ calcDuracao() }}</div>
          </div>
        </div>

        <!-- 2) Itens Verificados -->
        <h3>2) Itens Verificados</h3>
        @for (it of itens(); track it['id']) {
          <div class="item-row" [class.item-falha]="it['status'] === 'Falha'">
            <span class="item-nome">{{ it['item_nome'] }}</span>
            <span class="item-status" [class]="it['status'] === 'Falha' ? 'status-falha' : 'status-ok'">
              {{ it['status'] === 'Falha' ? '\u2716 Falha' : '\u2705 Ok' }}
            </span>
          </div>
          @if (it['status'] === 'Falha' && it['descricao_falha']) {
            <div class="item-desc falha-desc">
              <strong>Descrição da falha:</strong> {{ it['descricao_falha'] }}
            </div>
          }
          @if (it['valor_texto']) {
            <div class="item-desc texto-desc">
              {{ it['valor_texto'] }}
            </div>
          }
        }

        <!-- 3) Observações -->
        <h3>3) Observações</h3>
        <div class="field">
          <label>Anotações gerais</label>
          <div class="field-value obs-value">{{ data()!['observacoes'] || '' }}</div>
        </div>

        <!-- 4) Responsável -->
        <h3>4) Responsável</h3>
        <div class="field">
          <label>Verificado por</label>
          <div class="field-value">{{ data()!['operador_nome'] }}</div>
        </div>

        <!-- Ações -->
        <div class="detalhe-actions">
          <button class="btn-fechar" (click)="fechar()">Fechar Aba</button>
          <button class="btn-imprimir" (click)="imprimir()">Imprimir</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .badge-readonly { background: #047857; }
    h3 { font-size: .95rem; margin: 24px 0 8px; color: var(--text); }
    .two-cols { grid-template-columns: 1fr 1fr; }
    .three-cols { grid-template-columns: 1fr 1fr 1fr; }
    .item-row {
      display: flex; justify-content: space-between; align-items: center;
      padding: 10px 16px; border: 1px solid var(--border); border-radius: 6px;
      margin-bottom: 4px; background: #fff;
    }
    .item-falha { background: #fef2f2; }
    .item-nome { font-size: .9rem; }
    .item-status { font-size: .85rem; font-weight: 600; white-space: nowrap; }
    .status-ok { color: #16a34a; }
    .status-falha { color: #dc2626; }
    .item-desc {
      margin: 0 0 4px 0; padding: 6px 16px; font-size: .85rem;
      border-radius: 0 0 6px 6px; margin-top: -4px;
    }
    .falha-desc { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; border-top: none; }
    .texto-desc { background: #f8fafc; border: 1px solid var(--border); border-top: none; }
  `],
})
export class ChecklistDetalheComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private api = inject(ApiService);

  loading = signal(true);
  data = signal<Record<string, any> | null>(null);
  itens = signal<Record<string, any>[]>([]);

  ngOnInit(): void {
    const id = this.route.snapshot.queryParamMap.get('checklist_id');
    if (!id) { this.loading.set(false); return; }

    this.api.get<any>('/api/admin/checklist/detalhe', { checklist_id: +id }).subscribe({
      next: (res: any) => {
        const d = res?.data ?? res;
        this.data.set(d);
        this.itens.set(d?.itens ?? []);
        this.loading.set(false);
      },
      error: () => { this.loading.set(false); },
    });
  }

  calcDuracao(): string {
    const d = this.data();
    if (!d) return '-';
    const inicio = String(d['hora_inicio_testes'] || '');
    const termino = String(d['hora_termino_testes'] || '');
    if (!inicio || !termino) return '-';
    const toSec = (t: string) => {
      const p = t.split(':');
      return (+p[0]) * 3600 + (+p[1]) * 60 + (+p[2] || 0);
    };
    const diff = toSec(termino) - toSec(inicio);
    if (diff <= 0) return '-';
    const h = Math.floor(diff / 3600);
    const m = Math.floor((diff % 3600) / 60);
    const s = diff % 60;
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  fechar(): void { window.close(); }

  imprimir(): void { window.print(); }
}
