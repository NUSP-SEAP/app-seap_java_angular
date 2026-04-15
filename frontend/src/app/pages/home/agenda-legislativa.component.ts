import { Component, inject, OnInit, OnDestroy, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { environment } from '../../../environments/environment';

interface Reuniao {
  codigo: string;
  titulo: string;
  horario: string;
  local: string;
  situacao: string;
  comissao_sigla?: string;
  comissao_nome?: string;
  tipo_descricao?: string;
  tipo_presenca?: string;
  observacao_horario?: string;
  descricao?: string;
}

interface EscalaItem {
  sala_id: number;
  sala_nome: string;
  data_inicio: string;
  data_fim: string;
}

@Component({
  selector: 'app-agenda-legislativa',
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="page-header">
      <h1>Agenda Legislativa</h1>
      <a routerLink="/home" class="btn-voltar">Voltar</a>
    </div>

    @if (loading()) {
      <div class="card-custom">
        <p class="text-muted-sm">Carregando agenda...</p>
      </div>
    } @else if (!temEscala() && !plenarioPrincipal()) {
      <div class="card-custom">
        <p class="text-muted-sm">Você não está escalado para nenhum plenário hoje.</p>
      </div>
    } @else {

      <!-- Status da conexão -->
      <div class="status-bar" [class.conectado]="sseConectado()" [class.desconectado]="!sseConectado()">
        <span class="status-dot"></span>
        {{ sseConectado() ? 'Conectado — atualizações em tempo real' : 'Reconectando...' }}
        @if (ultimaAtualizacao()) {
          <span class="last-update">Última atualização: {{ ultimaAtualizacao() }}</span>
        }
      </div>

      <!-- Plenário Principal -->
      @if (plenarioPrincipal()) {
        <section class="agenda-section">
          <h2 class="section-title">
            <span class="section-icon">&#9679;</span>
            Plenário Principal
          </h2>
          @if (reunioesPlenario().length === 0) {
            <div class="card-custom">
              <p class="text-muted-sm">Nenhuma sessão plenária agendada para hoje.</p>
            </div>
          } @else {
            @for (r of reunioesPlenario(); track r.codigo) {
              <div class="card-custom reuniao-card">
                <div class="reuniao-header">
                  <span class="reuniao-horario">{{ r.horario }}</span>
                  <span class="reuniao-status" [class]="statusClass(r.situacao)">{{ r.situacao }}</span>
                </div>
                <div class="reuniao-titulo">{{ r.titulo }}</div>
                @if (r.descricao) {
                  <div class="reuniao-descricao">{{ r.descricao }}</div>
                }
                <div class="reuniao-meta">
                  <span>{{ r.tipo_descricao }}</span>
                  <span>{{ r.local }}</span>
                </div>
              </div>
            }
          }
        </section>
      }

      <!-- Comissões (por sala escalada) -->
      @for (escala of minhaEscala(); track escala.sala_id) {
        <section class="agenda-section">
          <h2 class="section-title">
            <span class="section-icon">&#9679;</span>
            {{ escala.sala_nome }}
          </h2>
          @if (getReunioesSala(escala.sala_id).length === 0) {
            <div class="card-custom">
              <p class="text-muted-sm">Nenhuma reunião agendada para este plenário hoje.</p>
            </div>
          } @else {
            @for (r of getReunioesSala(escala.sala_id); track r.codigo) {
              <div class="card-custom reuniao-card">
                <div class="reuniao-header">
                  <span class="reuniao-horario">{{ r.horario }}</span>
                  <span class="reuniao-status" [class]="statusClass(r.situacao)">{{ r.situacao }}</span>
                </div>
                <div class="reuniao-titulo">
                  @if (r.comissao_sigla) {
                    <span class="reuniao-sigla">{{ r.comissao_sigla }}</span>
                  }
                  {{ r.titulo }}
                </div>
                @if (r.comissao_nome) {
                  <div class="reuniao-comissao">{{ r.comissao_nome }}</div>
                }
                @if (r.observacao_horario) {
                  <div class="reuniao-descricao">{{ r.observacao_horario }}</div>
                }
                <div class="reuniao-meta">
                  @if (r.tipo_descricao) { <span>{{ r.tipo_descricao }}</span> }
                  @if (r.tipo_presenca) { <span>{{ r.tipo_presenca }}</span> }
                  <span>{{ r.local }}</span>
                </div>
              </div>
            }
          }
        </section>
      }
    }
  `,
  styles: [`
    .page-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:20px; }
    .btn-voltar {
      background:var(--card); color:var(--text); border:1px solid var(--border);
      border-radius:999px; padding:8px 20px; font-weight:600; text-decoration:none;
      font-size:.9rem; transition:background .15s;
      &:hover { background:var(--row-hover); }
    }

    .status-bar {
      display:flex; align-items:center; gap:8px; padding:8px 16px;
      border-radius:var(--radius); margin-bottom:20px;
      font-size:.85rem; font-weight:500;
      &.conectado { background:#f0fdf4; color:#166534; border:1px solid #bbf7d0; }
      &.desconectado { background:#fef2f2; color:#991b1b; border:1px solid #fecaca; }
    }
    .status-dot {
      width:8px; height:8px; border-radius:50%; flex-shrink:0;
      .conectado & { background:#22c55e; }
      .desconectado & { background:#ef4444; }
    }
    .last-update { margin-left:auto; font-size:.8rem; opacity:.7; }

    .agenda-section { margin-bottom:24px; }
    .section-title {
      display:flex; align-items:center; gap:8px;
      font-size:1.1rem; margin-bottom:12px;
    }
    .section-icon { color:var(--primary); font-size:.6rem; }

    .reuniao-card {
      margin-bottom:10px; padding:16px 20px;
      transition:border-color .15s;
      &:hover { border-color:var(--primary); }
    }
    .reuniao-header {
      display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;
    }
    .reuniao-horario {
      font-size:1.3rem; font-weight:700; color:var(--primary);
      font-variant-numeric: tabular-nums;
    }
    .reuniao-status {
      padding:3px 12px; border-radius:999px; font-size:.8rem; font-weight:600;
      &.status-agendada { background:#dbeafe; color:#1e40af; }
      &.status-aberta, &.status-em-andamento { background:#fef3c7; color:#92400e; }
      &.status-realizada, &.status-encerrada { background:#d1fae5; color:#065f46; }
      &.status-cancelada, &.status-adiada { background:#fee2e2; color:#991b1b; }
      &.status-suspensa { background:#fef3c7; color:#92400e; }
    }
    .reuniao-titulo { font-size:1rem; font-weight:600; margin-bottom:4px; }
    .reuniao-sigla {
      background:var(--primary); color:#fff; padding:2px 8px;
      border-radius:4px; font-size:.8rem; margin-right:6px;
    }
    .reuniao-comissao { font-size:.85rem; color:var(--muted); margin-bottom:4px; }
    .reuniao-descricao { font-size:.85rem; color:var(--muted); font-style:italic; margin-bottom:4px; }
    .reuniao-meta {
      display:flex; flex-wrap:wrap; gap:8px; font-size:.8rem; color:var(--muted);
      span { background:var(--table-header-bg); padding:2px 8px; border-radius:4px; }
    }
  `],
})
export class AgendaLegislativaComponent implements OnInit, OnDestroy {
  private api = inject(ApiService);
  private auth = inject(AuthService);

  loading = signal(true);
  plenarioPrincipal = signal(false);
  minhaEscala = signal<EscalaItem[]>([]);
  temEscala = signal(false);

  reunioesComissoes = signal<Reuniao[]>([]);
  reunioesPlenario = signal<Reuniao[]>([]);
  sseConectado = signal(false);
  ultimaAtualizacao = signal('');

  private eventSources: EventSource[] = [];

  ngOnInit(): void {
    // 1. Buscar escala do operador
    this.api.get<any>('/api/escala/minha').subscribe({
      next: (res: any) => {
        const escala: EscalaItem[] = res.data || [];
        const isPlenario = res.plenario_principal === true;

        this.minhaEscala.set(escala);
        this.plenarioPrincipal.set(isPlenario);
        this.temEscala.set(escala.length > 0);
        this.loading.set(false);

        // 2. Conectar SSE para cada sala + plenário
        if (isPlenario) this.conectarSSE(null, true);
        for (const e of escala) this.conectarSSE(e.sala_id, false);

        // Se não tem nada, pelo menos carregar dados REST
        if (!isPlenario && escala.length === 0) return;
        this.carregarDadosIniciais(escala, isPlenario);
      },
      error: () => { this.loading.set(false); },
    });
  }

  ngOnDestroy(): void {
    for (const es of this.eventSources) es.close();
    this.eventSources = [];
  }

  carregarDadosIniciais(escala: EscalaItem[], isPlenario: boolean): void {
    if (isPlenario) {
      this.api.get<any>('/api/agenda/plenario').subscribe({
        next: (res: any) => this.reunioesPlenario.set(res.data || []),
      });
    }
    // Carregar comissões (todas de uma vez, filtrar no front)
    if (escala.length > 0) {
      this.api.get<any>('/api/agenda/hoje').subscribe({
        next: (res: any) => this.reunioesComissoes.set(res.data || []),
      });
    }
  }

  getReunioesSala(salaId: number): Reuniao[] {
    return this.reunioesComissoes().filter(r => (r as any)['sala_id'] === salaId);
  }

  conectarSSE(salaId: number | null, plenarioPrincipal: boolean): void {
    const token = this.auth.getToken();
    let url = `${environment.apiBaseUrl}/api/agenda/stream?`;
    if (salaId != null) url += `sala_id=${salaId}&`;
    if (plenarioPrincipal) url += `plenario_principal=true&`;
    url += `token=${token}`;

    const es = new EventSource(url);
    this.eventSources.push(es);

    es.addEventListener('agenda', (event: any) => {
      const data = JSON.parse(event.data);
      if (data.tipo === 'plenario_principal') {
        this.reunioesPlenario.set(data.reunioes || []);
      } else if (data.tipo === 'comissao' && data.sala_id) {
        // Atualizar reuniões desta sala no cache geral
        const outras = this.reunioesComissoes().filter(r => (r as any)['sala_id'] !== data.sala_id);
        this.reunioesComissoes.set([...outras, ...(data.reunioes || [])]);
      } else if (data.tipo === 'todas') {
        this.reunioesComissoes.set(data.reunioes || []);
      }
      this.sseConectado.set(true);
      this.ultimaAtualizacao.set(this.formatarHora(data.atualizado_em));
    });

    es.onopen = () => this.sseConectado.set(true);
    es.onerror = () => {
      this.sseConectado.set(false);
      // O EventSource reconecta automaticamente
    };
  }

  statusClass(situacao: string): string {
    if (!situacao) return '';
    const s = situacao.toLowerCase().replace(/\s+/g, '-');
    return `status-${s}`;
  }

  private formatarHora(iso: string): string {
    if (!iso) return '';
    try {
      const d = new Date(iso);
      return d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch { return iso; }
  }
}
