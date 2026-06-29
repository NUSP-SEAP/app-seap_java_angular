import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-tecnico-home',
  standalone: true,
  imports: [RouterLink],
  template: `
    <h1>Página Principal — Técnicos</h1>

    <div class="grid-cards">
      <a routerLink="/tecnico/agenda" class="card-custom card-link">
        <strong>Agenda Legislativa</strong>
        <span class="text-muted-sm">Abrir</span>
      </a>
      <div class="card-custom card-placeholder">
        <strong>Em construção</strong>
        <span class="text-muted-sm">Novas funcionalidades em breve.</span>
      </div>
      @if (auth.isAdmin()) {
        <a routerLink="/admin" class="card-custom card-link card-admin">
          <strong>Painel Administrativo</strong>
          <span class="text-muted-sm">Voltar para Admin</span>
        </a>
      }
    </div>
  `,
  styles: [`
    .grid-cards { display:grid; grid-template-columns:repeat(3,1fr); gap:12px; margin-top:16px; }
    .card-link {
      display:flex; flex-direction:column; gap:4px;
      text-decoration:none; color:var(--text);
      transition:box-shadow .15s;
      &:hover { box-shadow:0 4px 12px rgba(0,0,0,.1); }
    }
    .card-placeholder { display:flex; flex-direction:column; gap:4px; opacity:.7; }
    .card-admin {
      border-color:#cbd5e1;
      background-color:#f8fafc;
      strong { color:var(--muted); }
    }
    .text-muted-sm { color:var(--muted); font-size:.85rem; }
  `],
})
export class TecnicoHomeComponent {
  auth = inject(AuthService);
}
