import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FmtTimePipe } from '../../shared/pipes/fmt-time.pipe';

@Component({
  selector: 'app-operacao-detalhe',
  standalone: true,
  imports: [FmtDatePipe, FmtTimePipe],
  template: `
    <div class="card-custom detalhe-card">
      @if (loading()) {
        <p class="text-muted-sm">Carregando...</p>
      } @else if (!d()) {
        <p class="text-muted-sm">Registro não encontrado.</p>
      } @else {
        <div class="detalhe-header">
          <div>
            <h1>Detalhe do Registro de Operação</h1>
            <p class="text-muted-sm">Visualização administrativa do registro nº {{ d()!['id'] }}</p>
          </div>
          <span class="badge-readonly">APENAS LEITURA</span>
        </div>

        <!-- Local -->
        <div class="field">
          <label>Local</label>
          <div class="field-value">{{ d()!['sala_nome'] }}</div>
        </div>

        @if (d()!['multi_operador']) {
          <!-- ═══ PLENÁRIO PRINCIPAL ═══ -->

          <!-- Descrição do Evento -->
          <div class="field">
            <label>Descrição do Evento</label>
            <div class="field-value">{{ d()!['nome_evento'] }}</div>
          </div>

          <!-- Data + Início + Término -->
          <div class="field-row grid-3">
            <div class="field">
              <label>Data</label>
              <div class="field-value">{{ d()!['data'] | fmtDate }}</div>
            </div>
            <div class="field">
              <label>Início da sessão</label>
              <div class="field-value">{{ d()!['horario_inicio'] | fmtTime }}</div>
            </div>
            <div class="field">
              <label>Término da sessão</label>
              <div class="field-value">{{ d()!['horario_termino'] | fmtTime }}</div>
            </div>
          </div>

          <!-- Suspensões -->
          <div class="field">
            <label>Suspensões</label>
            @if (asArray(d()!['suspensoes']).length > 0) {
              @for (s of asArray(d()!['suspensoes']); track $index) {
                <div class="field-value" style="margin-bottom:4px">
                  Suspensa em: {{ s['hora_suspensao'] }} &mdash; Reaberta em: {{ s['hora_reabertura'] }}
                </div>
              }
            } @else {
              <div class="field-value">Nenhuma</div>
            }
          </div>

          <!-- Trilha dos Gravadores -->
          <div class="field-row grid-2">
            <div class="field">
              <label>Trilha do Gravador 01</label>
              <div class="field-value">{{ d()!['usb_01'] || '-' }}</div>
            </div>
            <div class="field">
              <label>Trilha do Gravador 02</label>
              <div class="field-value">{{ d()!['usb_02'] || '-' }}</div>
            </div>
          </div>

          <!-- Observações -->
          <div class="field">
            <label>Observações</label>
            <div class="field-value obs-value">{{ d()!['observacoes'] || '' }}</div>
          </div>

          <!-- Houve Anormalidade -->
          <div class="field">
            <label>Houve Anormalidade?</label>
            <div class="field-value" [class]="d()!['houve_anormalidade'] ? 'val-falha' : ''">
              {{ d()!['houve_anormalidade'] ? 'Sim' : 'Não' }}
            </div>
          </div>

          <!-- Preenchido por -->
          <div class="field">
            <label>Preenchido por</label>
            <div class="field-value">{{ d()!['operador_nome'] }}</div>
          </div>

          <!-- Operadores da Sessão -->
          @if (d()!['operadores_sessao']) {
            <div class="field">
              <label>Operadores da Sessão</label>
              <div class="field-value">{{ asArray(d()!['operadores_sessao']).join(', ') }}</div>
            </div>
          }

        } @else {
          <!-- ═══ PLENÁRIOS NUMERADOS ═══ -->

          <!-- Atividade Legislativa -->
          <div class="field">
            <label>Atividade Legislativa</label>
            <div class="field-value">{{ d()!['comissao_nome'] || '-' }}</div>
          </div>

          <!-- Descrição do Evento -->
          <div class="field">
            <label>Descrição do Evento</label>
            <div class="field-value">{{ d()!['nome_evento'] }}</div>
          </div>

          <!-- Responsável pelo Evento -->
          <div class="field">
            <label>Responsável pelo Evento</label>
            <div class="field-value">{{ d()!['responsavel_evento'] || '-' }}</div>
          </div>

          <!-- Datas e Horários -->
          <div class="field-row grid-4">
            <div class="field">
              <label>Data</label>
              <div class="field-value">{{ d()!['data'] | fmtDate }}</div>
            </div>
            <div class="field">
              <label>Horário de Pauta</label>
              <div class="field-value">{{ d()!['horario_pauta'] | fmtTime }}</div>
            </div>
            <div class="field">
              <label>Hora de Início</label>
              <div class="field-value">{{ d()!['horario_inicio'] | fmtTime }}</div>
            </div>
            <div class="field">
              <label>Evento Encerrado?</label>
              <div class="field-value">{{ d()!['horario_termino'] ? 'Sim' : 'Não' }}</div>
            </div>
          </div>

          <div class="field-row grid-3">
            <div class="field">
              <label>Hora de Término</label>
              <div class="field-value">{{ d()!['horario_termino'] | fmtTime }}</div>
            </div>
            <div class="field">
              <label>Hora de Entrada (operador)</label>
              <div class="field-value">{{ d()!['hora_entrada'] | fmtTime }}</div>
            </div>
            <div class="field">
              <label>Hora de Saída (operador)</label>
              <div class="field-value">{{ d()!['hora_saida'] | fmtTime }}</div>
            </div>
          </div>

          <!-- Trilha dos Gravadores -->
          <div class="field-row grid-2">
            <div class="field">
              <label>Trilha do Gravador 01</label>
              <div class="field-value">{{ d()!['usb_01'] || '-' }}</div>
            </div>
            <div class="field">
              <label>Trilha do Gravador 02</label>
              <div class="field-value">{{ d()!['usb_02'] || '-' }}</div>
            </div>
          </div>

          <!-- Observações -->
          <div class="field">
            <label>Observações</label>
            <div class="field-value obs-value">{{ d()!['observacoes'] || '' }}</div>
          </div>

          <!-- Houve Anormalidade -->
          <div class="field">
            <label>Houve Anormalidade?</label>
            <div class="field-value" [class]="d()!['houve_anormalidade'] ? 'val-falha' : ''">
              {{ d()!['houve_anormalidade'] ? 'Sim' : 'Não' }}
            </div>
          </div>

          <!-- Operador Responsável -->
          <div class="field">
            <label>Operador Responsável</label>
            <div class="field-value">{{ d()!['operador_nome'] }}</div>
          </div>
        }

        <!-- Ações -->
        <div class="detalhe-actions">
          <button class="btn-fechar" (click)="fechar()">Fechar Aba</button>
          <button class="btn-imprimir" (click)="imprimir()">Imprimir</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .val-falha { color: #dc2626; font-weight: 700; }
  `],
})
export class OperacaoDetalheComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private api = inject(ApiService);

  loading = signal(true);
  d = signal<Record<string, any> | null>(null);

  ngOnInit(): void {
    const id = this.route.snapshot.queryParamMap.get('entrada_id');
    if (!id) { this.loading.set(false); return; }

    this.api.get<any>('/api/admin/operacao/detalhe', { entrada_id: +id }).subscribe({
      next: (res: any) => {
        this.d.set(res?.data ?? res);
        this.loading.set(false);
      },
      error: () => { this.loading.set(false); },
    });
  }

  asArray(v: unknown): any[] { return Array.isArray(v) ? v : []; }
  fechar(): void { window.close(); }
  imprimir(): void { window.print(); }
}
