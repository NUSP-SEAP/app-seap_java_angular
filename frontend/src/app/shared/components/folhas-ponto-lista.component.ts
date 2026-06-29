import { Component, Input, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { FmtDatePipe } from '../pipes/fmt-date.pipe';

export interface MinhaFolha {
  id: string;
  tipo: string;
  data_inicio: string;
  data_fim: string;
  publicado_em?: string;
}

/**
 * Lista (reutilizável) das folhas de ponto de uma pessoa, com visualizar/baixar.
 * Usada na página compartilhada /ponto (operador/técnico) e embutida em
 * /admin/ponto (para os admins que também são terceirizados). DRY (Regra #3).
 */
@Component({
  selector: 'app-folhas-ponto-lista',
  standalone: true,
  imports: [FmtDatePipe],
  template: `
    <div class="table-container">
      <table class="data-table">
        <thead><tr>
          <th>Período</th>
          <th>Tipo</th>
          <th>Disponível desde</th>
          <th>Ações</th>
        </tr></thead>
        <tbody>
          @for (f of folhas; track f.id) {
            <tr>
              <td><strong>{{ f.data_inicio | fmtDate }} — {{ f.data_fim | fmtDate }}</strong></td>
              <td>{{ f.tipo === 'MENSAL' ? 'Mensal' : 'Semanal' }}</td>
              <td>{{ f.publicado_em | fmtDate }}</td>
              <td class="acoes-cell">
                <button class="btn-xs" (click)="ver(f)">Visualizar</button>
                <button class="btn-xs" (click)="baixar(f)">Baixar</button>
                <button class="btn-xs" (click)="retificar(f)">Retificar</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    /* Desktop: layout atual preservado (larguras fixas + botões lado a lado). */
    @media (min-width: 641px) {
      .data-table th:nth-child(2) { width: 120px; }
      .data-table th:nth-child(3) { width: 160px; }
      .data-table th:nth-child(4) { width: 270px; }
      .btn-xs + .btn-xs { margin-left: 6px; }
    }
    /* Mobile: as 4 colunas cabem na tela; botões empilhados e centralizados. */
    @media (max-width: 640px) {
      .data-table { table-layout: fixed; }
      .data-table th, .data-table td {
        padding: 8px 4px; font-size: .72rem;
        white-space: normal; overflow-wrap: break-word; vertical-align: top;
      }
      /* "Semanal" e a data "dd/mm/aaaa" cabem em uma linha (sem reduzir a fonte). */
      .data-table th:nth-child(2), .data-table td:nth-child(2) { width: 64px; }
      .data-table th:nth-child(3), .data-table td:nth-child(3) { width: 88px; }
      .data-table th:nth-child(4), .data-table td:nth-child(4) { width: 92px; }
      .acoes-cell { text-align: center; }
      .acoes-cell .btn-xs {
        display: block; width: fit-content; max-width: 100%;
        margin: 0 auto 6px; padding: 5px 8px;
      }
      .acoes-cell .btn-xs:last-child { margin-bottom: 0; }
    }
  `],
})
export class FolhasPontoListaComponent {
  @Input() folhas: MinhaFolha[] = [];
  private api = inject(ApiService);
  private router = inject(Router);

  retificar(f: MinhaFolha): void {
    this.router.navigate(['/ponto/retificar', f.id]);
  }

  ver(f: MinhaFolha): void {
    this.api.getBlob(`/api/ponto/folha/${f.id}/download`).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => alert('Não foi possível abrir a folha de ponto.'),
    });
  }

  baixar(f: MinhaFolha): void {
    this.api.getBlob(`/api/ponto/folha/${f.id}/download`).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `ponto-${f.tipo.toLowerCase()}-${f.data_inicio}_a_${f.data_fim}.pdf`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () => alert('Não foi possível baixar a folha de ponto.'),
    });
  }
}
