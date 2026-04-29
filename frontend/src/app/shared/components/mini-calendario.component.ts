import { Component, computed, EventEmitter, Input, Output, signal } from '@angular/core';

interface DiaCelula {
  data: Date;
  dia: number;
  noMes: boolean;
  hoje: boolean;
  selecionado: boolean;
  desabilitado: boolean;
}

@Component({
  selector: 'app-mini-calendario',
  standalone: true,
  template: `
    <div class="cal">
      <div class="cal-header">
        <button class="cal-nav" (click)="prevMes()" [disabled]="!podeIrPrev()" aria-label="Mês anterior">‹</button>
        <span class="cal-titulo">{{ tituloMes() }}</span>
        <button class="cal-nav" (click)="nextMes()" [disabled]="!podeIrNext()" aria-label="Próximo mês">›</button>
      </div>
      <div class="cal-grid">
        @for (h of headers; track h) {
          <div class="cal-cabecalho">{{ h }}</div>
        }
        @for (d of dias(); track d.data.getTime()) {
          <button
            class="cal-dia"
            [class.fora-mes]="!d.noMes"
            [class.hoje]="d.hoje"
            [class.selecionado]="d.selecionado"
            [disabled]="d.desabilitado"
            (click)="selecionar(d)">
            {{ d.dia }}
          </button>
        }
      </div>
    </div>
  `,
  styles: [`
    .cal {
      display:inline-block; background:var(--card); border:1px solid var(--border);
      border-radius:var(--radius); padding:12px; user-select:none;
    }
    .cal-header {
      display:flex; align-items:center; justify-content:space-between; gap:8px;
      margin-bottom:8px;
    }
    .cal-titulo { font-weight:600; font-size:.95rem; text-transform:capitalize; }
    .cal-nav {
      background:transparent; border:1px solid var(--border); border-radius:6px;
      width:28px; height:28px; cursor:pointer; font-size:1.1rem; line-height:1;
      &:hover:not(:disabled) { background:var(--row-hover); }
      &:disabled { opacity:.3; cursor:not-allowed; }
    }
    .cal-grid {
      display:grid; grid-template-columns:repeat(7, 1fr); gap:2px;
    }
    .cal-cabecalho {
      text-align:center; font-size:.7rem; font-weight:600;
      color:var(--muted); padding:4px 0;
    }
    .cal-dia {
      background:transparent; border:none; cursor:pointer;
      width:32px; height:32px; border-radius:6px;
      font-size:.85rem; font-variant-numeric: tabular-nums;
      transition:background .1s;
      &:hover:not(:disabled) { background:var(--row-hover); }
      &.fora-mes { color:var(--muted); opacity:.4; }
      &.hoje { font-weight:700; box-shadow:inset 0 0 0 1px var(--primary); }
      &.selecionado { background:var(--primary); color:#fff; }
      &.selecionado.hoje { box-shadow:none; }
      &:disabled { opacity:.25; cursor:not-allowed; }
    }
  `],
})
export class MiniCalendarioComponent {
  @Input() set valorSelecionado(v: Date | null) {
    if (v) {
      this._selecionado.set(this.startOfDay(v));
      this._mesExibido.set(new Date(v.getFullYear(), v.getMonth(), 1));
    }
  }
  @Input() min: Date | null = null;
  @Input() max: Date | null = null;

  @Output() dataSelecionada = new EventEmitter<Date>();

  protected readonly headers = ['D', 'S', 'T', 'Q', 'Q', 'S', 'S'];

  private _selecionado = signal<Date>(this.startOfDay(new Date()));
  private _mesExibido = signal<Date>(new Date(new Date().getFullYear(), new Date().getMonth(), 1));

  protected tituloMes = computed(() => {
    const d = this._mesExibido();
    return d.toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
  });

  protected podeIrPrev = computed(() => {
    if (!this.min) return true;
    const m = this._mesExibido();
    const prev = new Date(m.getFullYear(), m.getMonth() - 1, 1);
    const minStart = new Date(this.min.getFullYear(), this.min.getMonth(), 1);
    return prev >= minStart;
  });

  protected podeIrNext = computed(() => {
    if (!this.max) return true;
    const m = this._mesExibido();
    const next = new Date(m.getFullYear(), m.getMonth() + 1, 1);
    const maxStart = new Date(this.max.getFullYear(), this.max.getMonth(), 1);
    return next <= maxStart;
  });

  protected dias = computed<DiaCelula[]>(() => {
    const m = this._mesExibido();
    const sel = this._selecionado();
    const hoje = this.startOfDay(new Date());

    // Primeiro dia da grade = domingo da semana que contém o dia 1 do mês
    const primeiroDoMes = new Date(m.getFullYear(), m.getMonth(), 1);
    const inicio = new Date(primeiroDoMes);
    inicio.setDate(inicio.getDate() - inicio.getDay());

    const result: DiaCelula[] = [];
    for (let i = 0; i < 42; i++) {
      const d = new Date(inicio);
      d.setDate(inicio.getDate() + i);
      result.push({
        data: d,
        dia: d.getDate(),
        noMes: d.getMonth() === m.getMonth(),
        hoje: this.sameDay(d, hoje),
        selecionado: this.sameDay(d, sel),
        desabilitado: this.foraDoLimite(d),
      });
    }
    return result;
  });

  protected prevMes(): void {
    const m = this._mesExibido();
    this._mesExibido.set(new Date(m.getFullYear(), m.getMonth() - 1, 1));
  }

  protected nextMes(): void {
    const m = this._mesExibido();
    this._mesExibido.set(new Date(m.getFullYear(), m.getMonth() + 1, 1));
  }

  protected selecionar(d: DiaCelula): void {
    if (d.desabilitado) return;
    this._selecionado.set(this.startOfDay(d.data));
    this._mesExibido.set(new Date(d.data.getFullYear(), d.data.getMonth(), 1));
    this.dataSelecionada.emit(d.data);
  }

  private foraDoLimite(d: Date): boolean {
    if (this.min && d < this.startOfDay(this.min)) return true;
    if (this.max && d > this.startOfDay(this.max)) return true;
    return false;
  }

  private sameDay(a: Date, b: Date): boolean {
    return a.getFullYear() === b.getFullYear()
      && a.getMonth() === b.getMonth()
      && a.getDate() === b.getDate();
  }

  private startOfDay(d: Date): Date {
    const r = new Date(d);
    r.setHours(0, 0, 0, 0);
    return r;
  }
}
