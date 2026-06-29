import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { environment } from '../../../environments/environment';

interface DashboardCard { id: string; titulo: string; }

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [RouterLink],
  template: `
    <h1>Painel Administrativo</h1>

    <!-- Cards de navegação (3 colunas × 2 linhas) -->
    <div class="grid-cards">
      <a routerLink="/home" class="card-custom card-link">Página Inicial dos Operadores</a>
      <a routerLink="/admin/operacao-audio" class="card-custom card-link">Operação de Áudio</a>
      <a routerLink="/admin/agenda" class="card-custom card-link">Agenda Legislativa</a>
      <a routerLink="/tecnico" class="card-custom card-link">Página Inicial dos Técnicos</a>
      <a routerLink="/admin/area-tecnica" class="card-custom card-link">Área Técnica</a>
      <a routerLink="/admin/gestao-pessoas" class="card-custom card-link">Gestão de Pessoas</a>
    </div>

    <!-- Indicadores (dashboard Metabase embutido) -->
    <section class="dash-section">
      @if (erroEmbed()) {
        <div class="erro-embed">{{ erroEmbed() }}</div>
      } @else if (iframeUrl()) {
        <iframe [src]="iframeUrl()!" title="Indicadores" frameborder="0" allowtransparency></iframe>
      } @else {
        <div class="info-embed">Carregando indicadores…</div>
      }
    </section>
  `,
  styles: [`
    .grid-cards { display:grid; grid-template-columns:repeat(3,1fr); gap:12px; margin-bottom:28px; }
    .card-link { display:flex; align-items:center; padding:16px 20px; text-decoration:none; color:var(--text); font-weight:600; font-size:.95rem; transition:box-shadow .15s; cursor:pointer; &:hover{box-shadow:0 4px 12px rgba(0,0,0,.1);} }
    .dash-section {
      background:var(--card); border:1px solid var(--border); border-radius:8px;
      overflow:hidden; min-height:400px; display:flex;
    }
    iframe { width:100%; height:2200px; border:0; }
    .info-embed, .erro-embed {
      flex:1; display:flex; align-items:center; justify-content:center;
      font-size:.9rem; color:var(--muted); padding:24px; text-align:center; min-height:400px;
    }
    .erro-embed { color:#b00020; }
  `],
})
export class AdminDashboardComponent implements OnInit {
  private http = inject(HttpClient);
  private sanitizer = inject(DomSanitizer);

  iframeUrl = signal<SafeResourceUrl | null>(null);
  erroEmbed = signal<string | null>(null);

  ngOnInit(): void {
    this.http.get<DashboardCard[]>(`${environment.apiBaseUrl}/api/admin/metabase/dashboards`, {
      withCredentials: true,
    }).subscribe({
      next: lista => {
        if (!lista.length) { this.erroEmbed.set('Nenhum dashboard de indicadores configurado.'); return; }
        const d = lista[0];
        this.http.get<{ url: string }>(
          `${environment.apiBaseUrl}/api/admin/metabase/dashboards/${d.id}/embed-url`,
          { withCredentials: true },
        ).subscribe({
          next: r => this.iframeUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(r.url)),
          error: e => this.erroEmbed.set(this.extrairMensagem(e, 'Não foi possível abrir o painel de indicadores.')),
        });
      },
      error: e => this.erroEmbed.set(this.extrairMensagem(e, 'Não foi possível carregar os indicadores.')),
    });
  }

  private extrairMensagem(e: any, fallback: string): string {
    return e?.error?.error || e?.error?.message || fallback;
  }
}
