import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';

export interface LookupItem { id: number | string; nome: string; nome_completo?: string; }

@Injectable({ providedIn: 'root' })
export class LookupService {
  private api = inject(ApiService);

  salas = signal<LookupItem[]>([]);
  operadores = signal<LookupItem[]>([]);
  comissoes = signal<LookupItem[]>([]);

  loadSalas(): void {
    this.api.get<any>('/api/forms/lookup/salas').subscribe(res => {
      this.salas.set(res.data || []);
    });
  }

  loadOperadores(): void {
    this.api.get<any>('/api/forms/lookup/operadores').subscribe(res => {
      this.operadores.set(res.data || []);
    });
  }

  loadComissoes(): void {
    this.api.get<any>('/api/forms/lookup/comissoes').subscribe(res => {
      this.comissoes.set(res.data || []);
    });
  }

  loadAll(): void {
    this.loadSalas();
    this.loadOperadores();
    this.loadComissoes();
  }
}
