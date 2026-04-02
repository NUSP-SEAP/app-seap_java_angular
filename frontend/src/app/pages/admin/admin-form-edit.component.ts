import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { LookupService } from '../../core/services/lookup.service';

interface EditItem { id?: number | null; nome: string; ativo: boolean; tipo_widget?: string; }

@Component({
  selector: 'app-admin-form-edit',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <h1>Edição de Formulários</h1>
    <a routerLink="/admin" class="back-link">← Voltar ao Painel</a>

    <!-- Cards de seleção -->
    <div class="grid-cards">
      <button class="card-custom card-link" [class.active]="activeEntity()==='salas'" (click)="selectEntity('salas')">
        <strong>Edição de Locais</strong><span class="text-muted-sm">Salas e plenários</span>
      </button>
      <button class="card-custom card-link" [class.active]="activeEntity()==='comissoes'" (click)="selectEntity('comissoes')">
        <strong>Edição de Comissões</strong><span class="text-muted-sm">Atividades legislativas</span>
      </button>
      <button class="card-custom card-link" [class.active]="activeEntity()==='sala_config'" (click)="selectEntity('sala_config')">
        <strong>Itens de Verificação</strong><span class="text-muted-sm">Config por sala</span>
      </button>
    </div>

    @if (activeEntity()) {
      <!-- Dropdown de sala (só para sala_config) -->
      @if (activeEntity() === 'sala_config') {
        <div style="margin-bottom:16px">
          <label style="font-weight:600">Sala:</label>
          <select [(ngModel)]="selectedSalaId" (ngModelChange)="onSalaSelect()" style="width:300px; margin-left:8px">
            <option value="">Selecione...</option>
            @for (s of lookup.salas(); track s.id) { <option [value]="s.id">{{ s.nome }}</option> }
          </select>
        </div>
      }

      @if (loading()) {
        <p style="color:var(--muted)">Carregando...</p>
      } @else {
        <div class="table-container">
          <table class="data-table">
            <thead><tr>
              <th style="width:60px">Posição</th>
              <th>Nome</th>
              @if (activeEntity() === 'sala_config') { <th style="width:130px">Tipo</th> }
              <th style="width:70px">Ativo</th>
            </tr></thead>
            <tbody>
              @for (item of items(); track $index) {
                <tr [class.inactive]="!item.ativo">
                  <td style="text-align:center">{{ item.ativo ? $index + 1 : '-' }}</td>
                  <td>
                    <input [(ngModel)]="item.nome" [name]="'nome_'+$index" (input)="dirty.set(true)"
                           style="width:100%; border:none; background:transparent; padding:4px"
                           placeholder="Nome do item">
                  </td>
                  @if (activeEntity() === 'sala_config') {
                    <td>
                      <select [(ngModel)]="item.tipo_widget" [name]="'tw_'+$index" (ngModelChange)="dirty.set(true)" style="width:100%">
                        <option value="radio">Ok/Falha</option>
                        <option value="text">Texto livre</option>
                      </select>
                    </td>
                  }
                  <td style="text-align:center">
                    <input type="checkbox" [(ngModel)]="item.ativo" [name]="'ativo_'+$index" (ngModelChange)="dirty.set(true)">
                  </td>
                </tr>
              }
              <!-- Linha para novo item -->
              <tr class="new-row">
                <td style="text-align:center; color:var(--muted)">+</td>
                <td>
                  <input [(ngModel)]="newItemNome" name="new_nome" placeholder="Novo item..." style="width:100%; padding:4px">
                </td>
                @if (activeEntity() === 'sala_config') {
                  <td><select [(ngModel)]="newItemTipo" name="new_tipo" style="width:100%"><option value="radio">Ok/Falha</option><option value="text">Texto livre</option></select></td>
                }
                <td style="text-align:center"><button class="btn-add" (click)="addItem()">+</button></td>
              </tr>
            </tbody>
          </table>
        </div>

        <div style="display:flex; justify-content:space-between; margin-top:16px; gap:8px; flex-wrap:wrap">
          <button class="btn-secondary-custom" (click)="cancel()">Cancelar</button>
          <div style="display:flex; gap:8px">
            @if (activeEntity() === 'sala_config') {
              <button class="btn-primary-custom" style="background:#059669" (click)="aplicarTodas()" [disabled]="saving()">Aplicar a Todos os Locais</button>
            }
            <button class="btn-primary-custom" (click)="save()" [disabled]="!dirty() || saving()">
              {{ saving() ? 'Salvando...' : 'Salvar Alterações' }}
            </button>
          </div>
        </div>
      }
    }
  `,
  styles: [`
    .back-link { color:var(--primary); font-size:.9rem; display:inline-block; margin-bottom:16px; }
    .grid-cards { display:grid; grid-template-columns:repeat(auto-fit,minmax(200px,1fr)); gap:12px; margin-bottom:24px; }
    .card-link { display:flex; flex-direction:column; gap:4px; text-align:left; cursor:pointer; border:2px solid transparent; transition:border-color .15s; &.active{border-color:var(--primary);} }
    .text-muted-sm { color:var(--muted); font-size:.85rem; }
    .table-container { overflow-x:auto; }
    .inactive td { opacity:.5; }
    .new-row td { background:#f8fafc; }
    .btn-add { background:var(--primary); color:#fff; border:none; border-radius:50%; width:24px; height:24px; cursor:pointer; font-weight:700; }
    .btn-secondary-custom { background:#fff; color:var(--text); border:1px solid var(--border); border-radius:999px; padding:10px 20px; font-weight:600; cursor:pointer; }
  `],
})
export class AdminFormEditComponent implements OnInit {
  private api = inject(ApiService);
  lookup = inject(LookupService);

  activeEntity = signal<string | null>(null);
  items = signal<EditItem[]>([]);
  loading = signal(false);
  saving = signal(false);
  dirty = signal(false);

  newItemNome = '';
  newItemTipo = 'radio';
  selectedSalaId = '';

  ngOnInit(): void { this.lookup.loadSalas(); }

  selectEntity(entity: string): void {
    this.activeEntity.set(entity);
    this.dirty.set(false);
    this.newItemNome = '';
    if (entity === 'sala_config') {
      this.items.set([]);
      this.selectedSalaId = '';
    } else {
      this.loadEntity(entity);
    }
  }

  onSalaSelect(): void {
    if (!this.selectedSalaId) { this.items.set([]); return; }
    this.loadSalaConfig(parseInt(this.selectedSalaId, 10));
  }

  private loadEntity(entity: string): void {
    this.loading.set(true);
    this.api.get<any>(`/api/admin/form-edit/${entity}/list`).subscribe({
      next: res => { this.items.set(res.items || []); this.loading.set(false); },
      error: () => { this.items.set([]); this.loading.set(false); },
    });
  }

  private loadSalaConfig(salaId: number): void {
    this.loading.set(true);
    this.api.get<any>(`/api/admin/form-edit/sala-config/${salaId}/list`).subscribe({
      next: res => {
        const items = (res.items || []).map((it: any) => ({ ...it, tipo_widget: it.tipo_widget || 'radio' }));
        this.items.set(items);
        this.loading.set(false);
      },
      error: () => { this.items.set([]); this.loading.set(false); },
    });
  }

  addItem(): void {
    if (!this.newItemNome.trim()) return;
    const item: EditItem = { id: null, nome: this.newItemNome.trim(), ativo: true };
    if (this.activeEntity() === 'sala_config') item.tipo_widget = this.newItemTipo;
    this.items.update(list => [...list, item]);
    this.newItemNome = '';
    this.dirty.set(true);
  }

  save(): void {
    const entity = this.activeEntity();
    if (!entity) return;
    this.saving.set(true);

    if (entity === 'sala_config') {
      const payload = { items: this.items().map(it => ({ nome: it.nome, tipo_widget: it.tipo_widget || 'radio', ativo: it.ativo })) };
      this.api.post<any>(`/api/admin/form-edit/sala-config/${this.selectedSalaId}/save`, payload).subscribe({
        next: res => { this.saving.set(false); this.dirty.set(false); if (res.ok) alert('Salvo!'); else alert(res.message || 'Erro'); },
        error: () => { this.saving.set(false); alert('Erro ao salvar.'); },
      });
    } else {
      const payload = { items: this.items() };
      this.api.post<any>(`/api/admin/form-edit/${entity}/save`, payload).subscribe({
        next: res => { this.saving.set(false); this.dirty.set(false); if (res.ok) alert('Salvo!'); else alert(res.message || 'Erro'); },
        error: () => { this.saving.set(false); alert('Erro ao salvar.'); },
      });
    }
  }

  aplicarTodas(): void {
    if (!this.selectedSalaId) { alert('Selecione uma sala primeiro.'); return; }
    if (!confirm('Replicar a configuração desta sala para TODAS as outras?')) return;
    this.saving.set(true);
    const payload = {
      source_sala_id: parseInt(this.selectedSalaId, 10),
      items: this.items().map(it => ({ nome: it.nome, tipo_widget: it.tipo_widget || 'radio', ativo: it.ativo })),
    };
    this.api.post<any>('/api/admin/form-edit/sala-config/aplicar-todas', payload).subscribe({
      next: res => { this.saving.set(false); if (res.ok) alert(`Configuração aplicada a ${res.salas_atualizadas} salas.`); else alert(res.message || 'Erro'); },
      error: () => { this.saving.set(false); alert('Erro ao aplicar.'); },
    });
  }

  cancel(): void {
    this.dirty.set(false);
    const entity = this.activeEntity();
    if (!entity) return;
    if (entity === 'sala_config' && this.selectedSalaId) this.loadSalaConfig(parseInt(this.selectedSalaId, 10));
    else if (entity !== 'sala_config') this.loadEntity(entity);
  }
}
