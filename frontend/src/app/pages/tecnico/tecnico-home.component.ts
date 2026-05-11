import { Component } from '@angular/core';

@Component({
  selector: 'app-tecnico-home',
  standalone: true,
  template: `
    <div class="card-custom" style="max-width:720px; margin:0 auto; text-align:center">
      <h1>Página Inicial — Técnicos</h1>
      <p class="text-muted-custom" style="margin-top:8px">Em construção.</p>
    </div>
  `,
  styles: [`
    .text-muted-custom { color: var(--muted); }
  `],
})
export class TecnicoHomeComponent {}
