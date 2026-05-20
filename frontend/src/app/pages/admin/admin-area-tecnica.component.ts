import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-admin-area-tecnica',
  standalone: true,
  imports: [RouterLink],
  template: `
    <h1>Área Técnica</h1>
    <a routerLink="/admin" class="back-link">&larr; Voltar ao Painel</a>

    <div class="grid-cards">
      <a routerLink="/admin/novo-tecnico" class="card-custom card-link">Cadastro de Técnico</a>
    </div>
  `,
  styles: [`
    .grid-cards { display:grid; grid-template-columns:repeat(3,1fr); gap:12px; margin:16px 0 28px; }
    .card-link { display:flex; align-items:center; padding:16px 20px; text-decoration:none; color:var(--text); font-weight:600; font-size:.95rem; transition:box-shadow .15s; cursor:pointer; &:hover{box-shadow:0 4px 12px rgba(0,0,0,.1);} }
  `],
})
export class AdminAreaTecnicaComponent {}
