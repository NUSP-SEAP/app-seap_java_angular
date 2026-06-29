import { Component, ElementRef, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FolhasPontoListaComponent, MinhaFolha } from '../../shared/components/folhas-ponto-lista.component';

type PessoaTipo = 'OPERADOR' | 'TECNICO' | 'ADMINISTRADOR';

interface Pessoa { id: string; nome: string; tipo: PessoaTipo; }

interface Pagina {
  id: string;
  numero_pagina: number;
  nome_extraido?: string;
  pessoa_id?: string;
  pessoa_tipo?: PessoaTipo;
  pessoa_nome?: string;
  status_match: 'AUTO' | 'MANUAL' | 'PENDENTE';
}

interface Lote {
  id: string;
  tipo: string;
  data_inicio: string;
  data_fim: string;
  status: 'REVISAO' | 'PUBLICADO';
  total_paginas: number;
  pendentes: number;
  criado_em?: string;
  publicado_em?: string;
  paginas?: Pagina[];
  _exp?: boolean;        // linha expandida (acordeão)
  emitirAviso?: boolean; // checkbox "Emitir aviso" (client-side; ausente = marcado)
}

@Component({
  selector: 'app-admin-ponto',
  standalone: true,
  imports: [FormsModule, RouterLink, FmtDatePipe, FolhasPontoListaComponent],
  template: `
    <h1>Ponto e Banco de Horas</h1>
    <a routerLink="/admin/gestao-pessoas" class="back-link">&larr; Voltar</a>

    <!-- ═══ Cards de navegação (mesmo padrão de /admin/form-edit) ═══ -->
    <div class="grid-cards">
      <button class="card-custom card-link" [class.active]="activeCard() === 'folhas'" (click)="selectCard('folhas')">
        <strong>Folhas de Ponto</strong><span class="text-muted-sm">Upload das folhas de ponto</span>
      </button>
      <button class="card-custom card-link" [class.active]="activeCard() === 'banco'" (click)="selectCard('banco')">
        <strong>Banco de Horas</strong>
      </button>
    </div>

    @if (activeCard() === 'folhas') {
    <!-- ═══ Upload ═══ -->
    <div class="card-custom" style="max-width:760px; margin-bottom:24px">
      <h2 class="form-title">Enviar PDF</h2>

      <div class="form-grid">
        <div class="form-row">
          <label>Tipo *</label>
          <select [(ngModel)]="tipo" name="tipo">
            <option value="MENSAL">Mensal</option>
            <option value="SEMANAL">Semanal</option>
          </select>
        </div>
        <div class="form-row">
          <label>De *</label>
          <input type="date" [(ngModel)]="dataInicio" name="data_inicio">
        </div>
        <div class="form-row">
          <label>Até *</label>
          <input type="date" [(ngModel)]="dataFim" name="data_fim">
        </div>
      </div>
      <div class="form-row">
        <label>Arquivo PDF *</label>
        <input #fileInput type="file" accept="application/pdf" (change)="onFileSelect($event)">
      </div>

      @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }

      <div style="margin-top:16px">
        <button class="btn-primary-custom" [disabled]="uploading()" (click)="onUpload()">
          {{ uploading() ? 'Processando...' : 'Enviar e processar' }}
        </button>
      </div>
    </div>

    <!-- ═══ Lotes enviados (linha expande/contrai em acordeão) ═══ -->
    <section>
      <div class="section-header"><h2>Lotes enviados</h2></div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th style="width:34px"></th>
            <th>Período</th><th style="width:110px">Tipo</th>
            <th style="width:90px; text-align:center">Páginas</th>
            <th style="width:110px; text-align:center">Pendentes</th>
            <th style="width:130px">Status</th>
            <th style="width:130px">Enviado em</th>
          </tr></thead>
          <tbody>
            @if (lotes().length === 0) {
              <tr><td colspan="7" class="empty-state">{{ loadingLotes() ? 'Carregando...' : 'Nenhum lote enviado ainda.' }}</td></tr>
            } @else {
              @for (l of lotes(); track l.id) {
                <tr class="row-clickable" (click)="toggleLote(l)">
                  <td><span class="btn-toggle">{{ l._exp ? '▼' : '▶' }}</span></td>
                  <td><strong>{{ l.data_inicio | fmtDate }} — {{ l.data_fim | fmtDate }}</strong></td>
                  <td>{{ l.tipo === 'MENSAL' ? 'Mensal' : 'Semanal' }}</td>
                  <td style="text-align:center">{{ l.total_paginas }}</td>
                  <td style="text-align:center" [style.color]="l.pendentes > 0 ? 'var(--color-red)' : 'var(--color-green)'">
                    <strong>{{ l.pendentes }}</strong>
                  </td>
                  <td>
                    @if (l.status === 'PUBLICADO') { <span class="badge-ok">Publicado</span> }
                    @else { <span class="badge-rev">Em revisão</span> }
                  </td>
                  <td>{{ l.criado_em | fmtDate }}</td>
                </tr>

                @if (l._exp) {
                  <tr class="accordion-row">
                    <td colspan="7">
                      @if (!l.paginas) {
                        <p class="text-muted-sm">Carregando páginas...</p>
                      } @else {
                        @if (l.status === 'REVISAO') {
                          <p class="text-muted-sm" style="margin:0 0 10px">
                            Confira o vínculo de cada página. Páginas <strong>pendentes</strong> não ficarão
                            visíveis a ninguém até serem vinculadas. Use “Ver PDF” em caso de dúvida.
                          </p>
                        } @else {
                          <p class="text-muted-sm" style="margin:0 0 10px">
                            Lote publicado em {{ l.publicado_em | fmtDate }}.
                          </p>
                        }

                        <table class="sub-table">
                          <thead><tr>
                            <th style="width:50px; text-align:center">Pág.</th>
                            <th style="width:120px">Vínculo</th>
                            <th>Operador / Técnico</th>
                            <th>Nome lido (dica)</th>
                            <th style="width:90px; text-align:center">PDF</th>
                          </tr></thead>
                          <tbody>
                            @for (p of l.paginas; track p.id) {
                              <tr>
                                <td style="text-align:center">{{ p.numero_pagina }}</td>
                                <td>
                                  @switch (p.status_match) {
                                    @case ('AUTO')   { <span class="badge-ok">Automático</span> }
                                    @case ('MANUAL') { <span class="badge-manual">Manual</span> }
                                    @default         { <span class="badge-falha">Pendente</span> }
                                  }
                                </td>
                                <td>
                                  @if (l.status === 'REVISAO') {
                                    <select class="pessoa-select" [ngModel]="valorPessoa(p)" (ngModelChange)="onAssign(l, p, $event)" [name]="'pessoa-' + p.id">
                                      <option value="">— pendente —</option>
                                      <optgroup label="Operadores">
                                        @for (o of operadores(); track o.id) {
                                          <option [value]="'OPERADOR:' + o.id">{{ o.nome }}</option>
                                        }
                                      </optgroup>
                                      <optgroup label="Técnicos">
                                        @for (t of tecnicos(); track t.id) {
                                          <option [value]="'TECNICO:' + t.id">{{ t.nome }}</option>
                                        }
                                      </optgroup>
                                      <optgroup label="Administradores">
                                        @for (a of administradores(); track a.id) {
                                          <option [value]="'ADMINISTRADOR:' + a.id">{{ a.nome }}</option>
                                        }
                                      </optgroup>
                                    </select>
                                  } @else {
                                    {{ p.pessoa_nome || '—' }}
                                    @if (p.pessoa_tipo) { <span class="text-muted-sm">({{ tipoLabel(p.pessoa_tipo) }})</span> }
                                  }
                                </td>
                                <td class="text-muted-sm">{{ p.nome_extraido || '—' }}</td>
                                <td style="text-align:center">
                                  <button class="btn-xs" (click)="preview(p)">Ver PDF</button>
                                </td>
                              </tr>
                            }
                          </tbody>
                        </table>

                        @if (l.status === 'REVISAO') {
                          <div style="display:flex; align-items:center; gap:12px; margin-top:12px">
                            <button class="btn-primary-custom" [disabled]="publicandoId() === l.id" (click)="publicar(l)">
                              {{ publicandoId() === l.id ? 'Publicando...' : 'Publicar lote' }}
                            </button>
                            <label class="aviso-ciente" style="margin:0" title="Avisa cada pessoa com folha no lote ao publicar">
                              <input type="checkbox" [checked]="l.emitirAviso !== false"
                                     (change)="l.emitirAviso = $any($event.target).checked">
                              Emitir aviso
                            </label>
                            @if (l.pendentes > 0) {
                              <span class="text-muted-sm" style="color:var(--color-red)">
                                {{ l.pendentes }} página(s) pendente(s) — ficarão indisponíveis se publicar agora.
                              </span>
                            }
                          </div>
                        }
                      }
                    </td>
                  </tr>
                }
              }
            }
          </tbody>
        </table>
      </div>
    </section>
    }

    @if (activeCard() === 'banco') {
      <div class="card-custom" style="margin-bottom:24px">
        <p class="text-muted-sm" style="margin:0">Em construção — em breve.</p>
      </div>
    }

    <!-- ═══ Minhas folhas (admins que também são terceirizados) ═══ -->
    @if (minhasFolhas().length > 0) {
      <section style="margin-top:8px">
        <div class="section-header"><h2>Minhas folhas de ponto</h2></div>
        <app-folhas-ponto-lista [folhas]="minhasFolhas()" />
      </section>
    }
  `,
  styles: [`
    .grid-cards { display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr)); gap:12px; margin-bottom:24px; }
    .card-link {
      display:flex; flex-direction:column; gap:4px; text-align:left; cursor:pointer;
      border:2px solid transparent; transition:border-color .15s;
      &.active { border-color:var(--primary); }
    }
    .form-grid { display:grid; grid-template-columns:repeat(3,1fr); gap:16px; }
    .form-row { margin-bottom:14px; }
    .form-row label { display:block; font-weight:500; font-size:.9375rem; margin-bottom:4px; }
    .form-row input, .form-row select { width:100%; }
    .error-box { background:#fef2f2; color:#b91c1c; border:1px solid #fca5a5; border-radius:8px; padding:10px 14px; font-size:.875rem; margin-top:8px; }
    .pessoa-select { width:100%; max-width:360px; }
    .badge-rev { color:#b45309; font-weight:600; }
    .badge-manual { color:var(--primary); font-weight:600; }
    @media (max-width:640px) { .form-grid { grid-template-columns:1fr; } }
  `],
})
export class AdminPontoComponent {
  private api = inject(ApiService);

  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;

  // Navegação por cards (mesmo padrão de /admin/form-edit)
  activeCard = signal<'folhas' | 'banco' | null>(null);

  // Upload
  tipo = 'MENSAL';
  dataInicio = '';
  dataFim = '';
  arquivo: File | null = null;
  uploading = signal(false);
  errorMsg = signal('');

  // Dados
  pessoas = signal<Pessoa[]>([]);
  operadores = computed(() => this.pessoas().filter(p => p.tipo === 'OPERADOR'));
  tecnicos = computed(() => this.pessoas().filter(p => p.tipo === 'TECNICO'));
  administradores = computed(() => this.pessoas().filter(p => p.tipo === 'ADMINISTRADOR'));
  lotes = signal<Lote[]>([]);
  loadingLotes = signal(true);
  publicandoId = signal<string | null>(null);
  minhasFolhas = signal<MinhaFolha[]>([]);   // folhas do admin logado (se também for terceirizado)

  ngOnInit(): void {
    this.loadPessoas();
    this.loadLotes();
    this.loadMinhasFolhas();
  }

  /** Alterna o card ativo; troca o conteúdo exibido (Folhas de Ponto / Banco de Horas). */
  selectCard(card: 'folhas' | 'banco'): void {
    this.activeCard.set(card);
  }

  tipoLabel(t?: PessoaTipo): string {
    return t === 'OPERADOR' ? 'Operador' : t === 'TECNICO' ? 'Técnico' : t === 'ADMINISTRADOR' ? 'Administrador' : '';
  }

  loadMinhasFolhas(): void {
    this.api.get<any>('/api/ponto/minhas-folhas').subscribe({
      next: res => this.minhasFolhas.set(res.data || []),
      error: () => this.minhasFolhas.set([]),
    });
  }

  // ── Carregamento ──
  loadPessoas(): void {
    this.api.get<any>('/api/admin/ponto/pessoas').subscribe({
      next: res => this.pessoas.set(res.data || []),
      error: () => this.pessoas.set([]),
    });
  }

  /** Carrega a lista de lotes; se `abrir` for passado, mescla o detalhe e expande aquele lote. */
  loadLotes(abrir?: Lote): void {
    this.loadingLotes.set(true);
    this.api.get<any>('/api/admin/ponto/lotes').subscribe({
      next: res => {
        const list: Lote[] = res.data || [];
        if (abrir) {
          const row = list.find(x => x.id === abrir.id);
          if (row) { Object.assign(row, abrir); row._exp = true; }
        }
        this.lotes.set(list);
        this.loadingLotes.set(false);
      },
      error: () => { this.lotes.set([]); this.loadingLotes.set(false); },
    });
  }

  /** Expande/contrai a linha do lote (acordeão). Carrega as páginas no 1º clique. */
  toggleLote(l: Lote): void {
    l._exp = !l._exp;
    if (l._exp && !l.paginas) {
      this.api.get<any>(`/api/admin/ponto/lote/${l.id}`).subscribe({
        next: res => { if (res.ok) { Object.assign(l, res.data); this.lotes.set([...this.lotes()]); } },
        error: err => { l._exp = false; this.lotes.set([...this.lotes()]); alert(err?.error?.message || 'Erro ao abrir o lote.'); },
      });
    }
    this.lotes.set([...this.lotes()]);
  }

  // ── Upload ──
  onFileSelect(event: Event): void {
    this.arquivo = (event.target as HTMLInputElement).files?.[0] || null;
  }

  onUpload(): void {
    this.errorMsg.set('');
    if (!this.arquivo) { this.errorMsg.set('Selecione o arquivo PDF.'); return; }
    if (!this.dataInicio || !this.dataFim) { this.errorMsg.set('Informe o início e o fim do período.'); return; }
    if (this.dataFim < this.dataInicio) { this.errorMsg.set('A data final não pode ser anterior à inicial.'); return; }

    this.uploading.set(true);
    const fd = new FormData();
    fd.append('arquivo', this.arquivo);
    fd.append('tipo', this.tipo);
    fd.append('data_inicio', this.dataInicio);
    fd.append('data_fim', this.dataFim);

    this.api.postForm<any>('/api/admin/ponto/upload', fd).subscribe({
      next: res => {
        this.uploading.set(false);
        if (res.ok) {
          this.arquivo = null;
          if (this.fileInput) this.fileInput.nativeElement.value = '';
          this.loadLotes(res.data);   // recarrega e já abre o lote recém-enviado
        } else {
          this.errorMsg.set(res.error || 'Erro ao processar o PDF.');
        }
      },
      error: err => {
        this.uploading.set(false);
        this.errorMsg.set(err?.error?.message || err?.error?.error || 'Erro ao processar o PDF.');
      },
    });
  }

  // ── Vínculo ──
  valorPessoa(p: Pagina): string {
    return p.pessoa_id ? `${p.pessoa_tipo}:${p.pessoa_id}` : '';
  }

  onAssign(l: Lote, p: Pagina, value: string): void {
    let body: { pessoa_id: string | null; pessoa_tipo: string | null } = { pessoa_id: null, pessoa_tipo: null };
    if (value) {
      const idx = value.indexOf(':');
      body = { pessoa_tipo: value.substring(0, idx), pessoa_id: value.substring(idx + 1) };
    }
    this.api.patch<any>(`/api/admin/ponto/lote/${l.id}/pagina/${p.id}`, body).subscribe({
      next: res => { if (res.ok) { Object.assign(l, res.data); this.lotes.set([...this.lotes()]); } },
      error: err => { alert(err?.error?.message || 'Erro ao vincular a página.'); this.recarregarLote(l); },
    });
  }

  /** Recarrega o detalhe de um lote (após erro) sem mudar o estado de expansão. */
  private recarregarLote(l: Lote): void {
    this.api.get<any>(`/api/admin/ponto/lote/${l.id}`).subscribe({
      next: res => { if (res.ok) { Object.assign(l, res.data); this.lotes.set([...this.lotes()]); } },
    });
  }

  // ── Preview ──
  preview(p: Pagina): void {
    this.api.getBlob(`/api/admin/ponto/pagina/${p.id}/preview`).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => alert('Não foi possível abrir o PDF da página.'),
    });
  }

  // ── Publicação ──
  publicar(l: Lote): void {
    const aviso = l.pendentes > 0
      ? `\n\nAtenção: ${l.pendentes} página(s) pendente(s) não ficarão visíveis a ninguém.`
      : '';
    if (!confirm(`Publicar este lote? As folhas vinculadas ficarão disponíveis para os operadores/técnicos.${aviso}`)) return;

    this.publicandoId.set(l.id);
    this.api.post<any>(`/api/admin/ponto/lote/${l.id}/publicar`, { emitir_aviso: l.emitirAviso !== false }).subscribe({
      next: res => {
        this.publicandoId.set(null);
        if (res.ok) { Object.assign(l, res.data); this.lotes.set([...this.lotes()]); }
      },
      error: err => { this.publicandoId.set(null); alert(err?.error?.message || 'Erro ao publicar.'); },
    });
  }
}
