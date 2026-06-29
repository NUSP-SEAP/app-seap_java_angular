import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { homeRouteForRole } from '../../core/helpers/auth.helpers';
import { FolhasPontoListaComponent, MinhaFolha } from './folhas-ponto-lista.component';
import { RegistroManualPontoComponent } from './registro-manual-ponto.component';

/**
 * Página "Ponto e Banco" compartilhada por operadores e técnicos (DRY).
 * Navegação por cards (mesmo padrão de /admin/ponto): cada card abre o seu
 * conteúdo; clicar em outro card oculta o anterior. O backend (/api/ponto/**)
 * usa o usuário autenticado, então o mesmo componente serve os dois papéis.
 */
@Component({
  selector: 'app-ponto-banco',
  standalone: true,
  imports: [RouterLink, FolhasPontoListaComponent, RegistroManualPontoComponent],
  template: `
    <h1>Ponto e Banco de Horas</h1>
    <a [routerLink]="backLink()" class="back-link">&larr; Voltar</a>

    <!-- ═══ Acordeão: o conteúdo de cada card abre logo abaixo dele e empurra os demais ═══ -->
    <div class="acordeao">
      <!-- Minhas folhas de ponto -->
      <button class="card-custom card-link" [class.active]="activeCard() === 'folhas'" (click)="toggleCard('folhas')">
        <strong>Minhas folhas de ponto</strong>
      </button>
      <div class="painel" [hidden]="activeCard() !== 'folhas'">
        @if (loading()) {
          <p class="text-muted-sm">Carregando...</p>
        } @else if (folhas().length === 0) {
          <p class="text-muted-sm">Nenhuma folha de ponto disponível ainda.</p>
        } @else {
          <app-folhas-ponto-lista [folhas]="folhas()" />
        }
      </div>

      <!-- Registro manual de ponto -->
      <button class="card-custom card-link" [class.active]="activeCard() === 'manual'" (click)="toggleCard('manual')">
        <strong>Registro manual de ponto</strong>
      </button>
      <div class="painel" [hidden]="activeCard() !== 'manual'">
        <app-registro-manual-ponto />
      </div>

      <!-- Banco de horas -->
      <button class="card-custom card-link" [class.active]="activeCard() === 'banco'" (click)="toggleCard('banco')">
        <strong>Banco de horas</strong>
      </button>
      <div class="painel" [hidden]="activeCard() !== 'banco'">
        <p class="text-muted-sm" style="margin:0">Em construção — em breve.</p>
      </div>
    </div>
  `,
  styles: [`
    .acordeao { display:flex; flex-direction:column; gap:12px; margin-bottom:24px; }
    .card-link {
      display:flex; flex-direction:column; gap:4px; text-align:left; cursor:pointer; width:100%;
      border:2px solid transparent; transition:border-color .15s;
      &.active { border-color:var(--primary); }
    }
    /* Painel oculto não ocupa espaço (sem gap residual no flex). Mantém o DOM montado
       p/ preservar o que o usuário digitou ao fechar/reabrir. */
    .painel[hidden] { display:none; }
  `],
})
export class PontoBancoComponent {
  private api = inject(ApiService);
  private auth = inject(AuthService);

  // Navegação por cards (mesmo padrão de /admin/ponto)
  activeCard = signal<'folhas' | 'manual' | 'banco' | null>(null);

  folhas = signal<MinhaFolha[]>([]);
  loading = signal(true);
  backLink = computed(() => homeRouteForRole(this.auth.role()));

  ngOnInit(): void {
    this.api.get<any>('/api/ponto/minhas-folhas').subscribe({
      next: res => { this.folhas.set(res.data || []); this.loading.set(false); },
      error: () => { this.folhas.set([]); this.loading.set(false); },
    });
  }

  /**
   * Abre o card; clicar de novo no mesmo card fecha (acordeão de 1 aberto por vez).
   * O conteúdo é ocultado via [hidden] no template — o componente permanece montado,
   * preservando o que o usuário já digitou (ex.: horas no registro manual).
   */
  toggleCard(card: 'folhas' | 'manual' | 'banco'): void {
    this.activeCard.update(cur => (cur === card ? null : card));
  }
}
