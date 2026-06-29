import { Component, computed, signal } from '@angular/core';

const MESES = [
  'Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho',
  'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro',
];
/** getDay(): 0=domingo … 6=sábado */
const DOW = ['dom', 'seg', 'ter', 'qua', 'qui', 'sex', 'sáb'];

interface DiaLinha {
  dia: number;
  /** "dd/mm - xxx" (ex.: "25/06 - qui") */
  rotulo: string;
}

/**
 * ESQUELETO da página "Registro manual de ponto" (card de /ponto).
 *
 * Mobile-first: em vez de uma tabela larga de colunas fixas, cada DIA é um
 * mini-grid próprio (5 colunas × 3 linhas) empilhado verticalmente. Navega-se
 * por mês com ‹ › ou pelos selects (só 2026, por enquanto).
 *
 * Sem dados / sem backend ainda: os campos de hora e os botões de câmera são
 * placeholders visuais; os saldos mostram "±--:--". A persistência, o cálculo
 * de saldo (regra Secullum validada) e a foto entram nas próximas etapas.
 */
@Component({
  selector: 'app-registro-manual-ponto',
  standalone: true,
  imports: [],
  template: `
    <section class="reg-manual">
      <!-- ═══ Seletor de mês/ano (centralizado) ═══ -->
      <div class="seletor">
        <button type="button" class="nav-btn" (click)="voltarMes()"
                [disabled]="!podeVoltar()" aria-label="Mês anterior">‹</button>

        <select class="sel sel-mes" [value]="mes()" (change)="onSelectMes($event)" aria-label="Mês">
          @for (m of meses; track $index) {
            <option [value]="$index + 1">{{ m }}</option>
          }
        </select>

        <select class="sel sel-ano" aria-label="Ano">
          <option [value]="ANO">{{ ANO }}</option>
        </select>

        <button type="button" class="nav-btn" (click)="avancarMes()"
                [disabled]="!podeAvancar()" aria-label="Próximo mês">›</button>
      </div>

      <!-- ═══ Lista de dias (1 mini-grid por dia) ═══ -->
      <div class="dias">
        @for (l of dias(); track l.dia) {
          <div class="dia-card">
            <div class="col-dia">{{ l.rotulo }}</div>

            <div class="cel ent1"><span class="lbl">Ent. 1</span>
              <input class="hora" inputmode="numeric" maxlength="5" placeholder="--:--" autocomplete="off"></div>
            <div class="cel sai1"><span class="lbl">Saí. 1</span>
              <input class="hora" inputmode="numeric" maxlength="5" placeholder="--:--" autocomplete="off"></div>
            <div class="cel ent2"><span class="lbl">Ent. 2</span>
              <input class="hora" inputmode="numeric" maxlength="5" placeholder="--:--" autocomplete="off"></div>
            <div class="cel sai2"><span class="lbl">Saí. 2</span>
              <input class="hora" inputmode="numeric" maxlength="5" placeholder="--:--" autocomplete="off"></div>

            <button type="button" class="cam cam1" aria-label="Foto Ent. 1"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg></button>
            <button type="button" class="cam cam2" aria-label="Foto Saí. 1"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg></button>
            <button type="button" class="cam cam3" aria-label="Foto Ent. 2"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg></button>
            <button type="button" class="cam cam4" aria-label="Foto Saí. 2"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg></button>

            <div class="resumo total"><span class="lbl">Total dia</span><strong>±--:--</strong></div>
            <div class="resumo banco"><span class="lbl">Banco</span><strong>±--:--</strong></div>
          </div>
        }

        <!-- linha extra 1: em branco -->
        <div class="linha-branca"></div>

        <!-- linha extra 2: TOTAIS -->
        <div class="totais-card">
          <div class="totais-lbl">TOTAIS</div>
          <div class="resumo total"><span class="lbl">Total dia</span><strong>±--:--</strong></div>
          <div class="resumo banco"><span class="lbl">Banco</span><strong>±--:--</strong></div>
        </div>
      </div>
    </section>
  `,
  styles: [`
    .reg-manual { margin: 0 auto; max-width: 680px; }

    /* ── Seletor de mês/ano ── */
    .seletor {
      display: flex; align-items: center; justify-content: center;
      gap: 8px; margin: 4px 0 18px;
    }
    .nav-btn {
      width: 40px; height: 40px; border-radius: 999px;
      border: 1px solid var(--border); background: #fff; color: var(--primary);
      font-size: 1.4rem; line-height: 1; cursor: pointer; padding: 0;
      display: flex; align-items: center; justify-content: center;
    }
    .nav-btn:hover:not(:disabled) { background: var(--row-hover); }
    .nav-btn:disabled { opacity: .35; cursor: default; }
    .sel {
      height: 40px; border: 1px solid var(--border); border-radius: 8px;
      background: #fff; color: var(--text); font-size: .95rem; padding: 0 8px;
    }
    .sel-mes { min-width: 120px; }
    .sel-ano { min-width: 84px; }

    /* ── Card de um dia (5 colunas × 3 linhas) ── */
    .dia-card {
      display: grid;
      grid-template-columns: minmax(52px, 0.8fr) 1fr 1fr 1fr 1fr;
      grid-template-rows: auto auto auto;
      gap: 4px 6px;
      border: 1px solid var(--border); border-radius: 10px;
      padding: 8px; margin-bottom: 8px;
    }
    .col-dia {
      grid-column: 1; grid-row: 1 / 4;
      display: flex; align-items: center;
      font-weight: 700; font-size: .8rem; color: var(--primary);
      line-height: 1.15; word-break: break-word;
    }
    .ent1 { grid-column: 2; grid-row: 1; }
    .sai1 { grid-column: 3; grid-row: 1; }
    .ent2 { grid-column: 4; grid-row: 1; }
    .sai2 { grid-column: 5; grid-row: 1; }
    .cam1 { grid-column: 2; grid-row: 2; }
    .cam2 { grid-column: 3; grid-row: 2; }
    .cam3 { grid-column: 4; grid-row: 2; }
    .cam4 { grid-column: 5; grid-row: 2; }
    .total { grid-column: 2 / 4; grid-row: 3; }
    .banco { grid-column: 4 / 6; grid-row: 3; }

    /* rótulo + campo de hora: lado a lado quando cabe; quebra (empilha) no celular */
    .cel { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 4px; }
    .lbl { font-size: .62rem; font-weight: 600; color: #64748b; white-space: nowrap; }
    .hora {
      flex: 1 1 46px; min-width: 46px; width: 100%;
      height: 30px; text-align: center; font-variant-numeric: tabular-nums;
      border: 1px solid var(--border); border-radius: 6px; padding: 0 2px; font-size: .9rem;
    }
    .cam {
      justify-self: stretch; height: 28px; cursor: pointer;
      border: 1px solid var(--border); border-radius: 6px; background: #f8fafc; color: #475569;
      display: flex; align-items: center; justify-content: center; padding: 0;
    }
    .cam:hover { background: var(--row-hover); }
    .cam svg { display: block; width: 17px; height: 17px; }

    .resumo {
      display: flex; align-items: center; justify-content: space-between; gap: 6px;
      background: #f1f5f9; border-radius: 6px; padding: 4px 8px;
      font-size: .78rem;
    }
    .resumo .lbl { color: #475569; }
    .resumo strong { font-variant-numeric: tabular-nums; color: var(--text); }

    /* ── Linhas extras ── */
    .linha-branca { height: 14px; }
    .totais-card {
      display: grid;
      grid-template-columns: minmax(52px, 0.8fr) 1fr 1fr 1fr 1fr;
      gap: 4px 6px; align-items: center;
      border: 1px solid var(--border); border-radius: 10px;
      padding: 8px; background: #eef2f7; font-weight: 700;
    }
    .totais-lbl { grid-column: 1; font-size: .82rem; color: var(--primary); letter-spacing: .03em; }
    .totais-card .total { grid-column: 2 / 4; }
    .totais-card .banco { grid-column: 4 / 6; }
  `],
})
export class RegistroManualPontoComponent {
  readonly ANO = 2026;
  readonly meses = MESES;

  /** Mês selecionado (1–12). Inicia no mês atual se estivermos em 2026, senão Janeiro. */
  mes = signal<number>(this.mesInicial());

  podeVoltar = computed(() => this.mes() > 1);
  podeAvancar = computed(() => this.mes() < 12);

  /** Uma linha por dia do mês, no formato "dd/mm - xxx". */
  dias = computed<DiaLinha[]>(() => {
    const m = this.mes();
    const qtd = new Date(this.ANO, m, 0).getDate();
    const out: DiaLinha[] = [];
    for (let d = 1; d <= qtd; d++) {
      const dt = new Date(this.ANO, m - 1, d);
      const dd = String(d).padStart(2, '0');
      const mm = String(m).padStart(2, '0');
      out.push({ dia: d, rotulo: `${dd}/${mm} - ${DOW[dt.getDay()]}` });
    }
    return out;
  });

  private mesInicial(): number {
    const hoje = new Date();
    return hoje.getFullYear() === this.ANO ? hoje.getMonth() + 1 : 1;
  }

  voltarMes(): void { if (this.podeVoltar()) this.mes.update(v => v - 1); }
  avancarMes(): void { if (this.podeAvancar()) this.mes.update(v => v + 1); }
  onSelectMes(ev: Event): void { this.mes.set(Number((ev.target as HTMLSelectElement).value)); }
}
