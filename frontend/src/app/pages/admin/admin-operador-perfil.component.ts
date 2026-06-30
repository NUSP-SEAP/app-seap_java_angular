import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-admin-operador-perfil',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="card-custom perfil-card">
      @if (loading()) {
        <p class="text-muted-sm">Carregando...</p>
      } @else if (!op()) {
        <div class="error-box">{{ errorMsg() || 'Operador não encontrado.' }}</div>
        <div class="perfil-acoes">
          <button class="btn-secondary-custom" (click)="voltar()">← Voltar</button>
        </div>
      } @else {
        <!-- Cabeçalho: foto + nome -->
        <div class="perfil-header">
          <div class="avatar-wrap">
            <img class="avatar-lg" [src]="fotoUrl() || ANONIMO" (error)="onImgError($event)" alt="Foto do operador">
            @if (editing()) {
              <label class="btn-troca-foto">
                Trocar foto
                <input type="file" accept="image/*" (change)="onFileSelect($event)" hidden>
              </label>
            }
          </div>
          <h1 class="perfil-nome">{{ op()!['nome_completo'] }}</h1>
        </div>

        <!-- Campos -->
        <div class="perfil-campos">
          <div class="perfil-row">
            <span class="perfil-label">Nome:</span>
            @if (editing()) {
              <input class="perfil-input" [(ngModel)]="nomeCompleto">
            } @else {
              <span class="perfil-valor">{{ op()!['nome_completo'] }}</span>
            }
          </div>

          <div class="perfil-row">
            <span class="perfil-label">Nome de Chamada:</span>
            @if (editing()) {
              <input class="perfil-input" [(ngModel)]="nomeExibicao">
            } @else {
              <span class="perfil-valor">{{ op()!['nome_exibicao'] }}</span>
            }
          </div>

          <div class="perfil-row">
            <span class="perfil-label">E-mail:</span>
            @if (editing()) {
              <input class="perfil-input" type="email" [(ngModel)]="email">
            } @else {
              <span class="perfil-valor">{{ op()!['email'] }}</span>
            }
          </div>

          <div class="perfil-row">
            <span class="perfil-label">Turno:</span>
            @if (editing()) {
              <select class="perfil-input perfil-input-sm" [(ngModel)]="turno">
                <option value="M">Matutino</option>
                <option value="V">Vespertino</option>
              </select>
            } @else {
              <span class="perfil-valor">{{ turnoLabel() }}</span>
            }
          </div>

          <div class="perfil-row">
            <span class="perfil-label">Carga Horária:</span>
            @if (editing()) {
              <select class="perfil-input perfil-input-sm" [(ngModel)]="cargaHoraria">
                <option value="">—</option>
                <option value="30">30H</option>
                <option value="40">40H</option>
              </select>
            } @else {
              <span class="perfil-valor">{{ cargaLabel() }}</span>
            }
          </div>

          <div class="perfil-row">
            <span class="perfil-label">Horário de Trabalho:</span>
            @if (editing()) {
              <span class="horario-edit">
                <input class="perfil-input perfil-input-hora" [value]="horaInicio" (input)="onHoraInput($event, 'inicio')" inputmode="numeric" placeholder="HH:MM" maxlength="5">
                <span class="horario-as">às</span>
                <input class="perfil-input perfil-input-hora" [value]="horaFim" (input)="onHoraInput($event, 'fim')" inputmode="numeric" placeholder="HH:MM" maxlength="5">
              </span>
            } @else {
              <span class="perfil-valor">{{ horarioLabel() }}</span>
            }
          </div>

          <!-- Checkboxes -->
          <div class="perfil-check">
            <input type="checkbox" id="chk-apto" [(ngModel)]="plenarioPrincipal" [disabled]="!editing()" (change)="onPlenarioChange()">
            <label for="chk-apto">Apto a operar no Plenário Principal</label>
          </div>
          <div class="perfil-check perfil-check-indent">
            <input type="checkbox" id="chk-fixo" [(ngModel)]="plenarioPrincipalFixo" [disabled]="!editing() || !plenarioPrincipal">
            <label for="chk-fixo" [style.color]="plenarioPrincipal ? null : '#94a3b8'">Operador fixo do Plenário Principal</label>
          </div>
          <div class="perfil-check">
            <input type="checkbox" id="chk-escala" [(ngModel)]="participaEscala" [disabled]="!editing()">
            <label for="chk-escala">Participa da Escala</label>
          </div>
        </div>

        @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }

        <!-- Ações -->
        <div class="perfil-acoes">
          @if (editing()) {
            <button class="btn-secondary-custom" (click)="cancelar()" [disabled]="saving()">Cancelar</button>
            <button class="btn-primary-custom" (click)="salvar()" [disabled]="saving()">{{ saving() ? 'Salvando...' : 'Salvar' }}</button>
          } @else {
            <button class="btn-secondary-custom" (click)="voltar()">← Voltar</button>
            <button class="btn-primary-custom" (click)="entrarEdicao()">Editar</button>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .perfil-card { max-width: 640px; margin: 0 auto; }
    .perfil-header { display:flex; align-items:center; gap:24px; margin-bottom:28px; flex-wrap:wrap; }
    .avatar-wrap { display:flex; flex-direction:column; align-items:center; gap:8px; }
    .avatar-lg { width:120px; height:120px; border-radius:50%; object-fit:cover; border:3px solid var(--senado-azul); }
    .btn-troca-foto { font-size:.75rem; color:var(--primary); cursor:pointer; border:1px solid var(--border); border-radius:999px; padding:4px 12px; background:#fff; }
    .btn-troca-foto:hover { background:var(--row-hover); }
    .perfil-nome { margin:0; font-size:1.5rem; }
    .perfil-campos { display:flex; flex-direction:column; gap:14px; }
    .perfil-row { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
    .perfil-label { font-weight:500; color:var(--text); min-width:150px; }
    .perfil-valor { font-weight:700; }
    .perfil-input { flex:1; min-width:200px; }
    .perfil-input-sm { flex:0 0 auto; min-width:110px; }
    .perfil-input-hora { flex:0 0 auto; width:80px; min-width:0; text-align:center; }
    .horario-edit { display:flex; align-items:center; gap:8px; }
    .horario-as { color:var(--muted); }
    .perfil-check { display:flex; align-items:center; gap:8px; }
    .perfil-check input { width:auto; margin:0; cursor:pointer; }
    .perfil-check input:disabled { cursor:default; }
    .perfil-check label { font-weight:500; cursor:pointer; }
    .perfil-check-indent { padding-left:24px; }
    .perfil-acoes { display:flex; justify-content:space-between; margin-top:28px; gap:12px; }
    .btn-secondary-custom { background:#fff; color:var(--text); border:1px solid var(--border); border-radius:999px; padding:10px 20px; font-weight:600; cursor:pointer; text-decoration:none; }
    .error-box { background:#fef2f2; color:#b91c1c; border:1px solid #fca5a5; border-radius:8px; padding:10px 14px; font-size:.875rem; margin-top:16px; }
  `],
})
export class AdminOperadorPerfilComponent implements OnInit {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  private id = '';
  op = signal<Record<string, any> | null>(null);
  loading = signal(true);
  editing = signal(false);
  saving = signal(false);
  errorMsg = signal('');

  // Campos editáveis
  nomeCompleto = '';
  nomeExibicao = '';
  email = '';
  turno = 'M';
  cargaHoraria = '';   // '', '30' ou '40'
  horaInicio = '';
  horaFim = '';
  plenarioPrincipal = false;
  plenarioPrincipalFixo = false;
  participaEscala = false;

  foto: File | null = null;
  fotoPreview = signal('');

  readonly ANONIMO = 'assets/imgs/usuario_anonimo.jpg';

  private readonly horaRe = /^([01]\d|2[0-3]):[0-5]\d$/;

  /** Se a foto cadastrada não existir (404), cai para a imagem anônima (sem loop). */
  onImgError(event: Event): void {
    const img = event.target as HTMLImageElement;
    if (!img.src.includes('usuario_anonimo')) img.src = this.ANONIMO;
  }

  ngOnInit(): void {
    this.id = this.route.snapshot.queryParamMap.get('id') || '';
    if (!this.id) { this.errorMsg.set('Operador não informado.'); this.loading.set(false); return; }
    this.carregar();
  }

  private carregar(): void {
    this.loading.set(true);
    this.api.get<any>(`/api/admin/operador/${this.id}`).subscribe({
      next: res => { const o = res?.operador ?? res; this.op.set(o); this.popularCampos(o); this.loading.set(false); },
      error: () => { this.errorMsg.set('Erro ao carregar o operador.'); this.loading.set(false); },
    });
  }

  private popularCampos(o: Record<string, any>): void {
    this.nomeCompleto = o['nome_completo'] || '';
    this.nomeExibicao = o['nome_exibicao'] || '';
    this.email = o['email'] || '';
    this.turno = o['turno'] || 'M';
    this.cargaHoraria = o['carga_horaria'] != null ? String(o['carga_horaria']) : '';
    this.horaInicio = o['horario_trabalho_inicio'] || '';
    this.horaFim = o['horario_trabalho_fim'] || '';
    this.plenarioPrincipal = o['plenario_principal'] === true || o['plenario_principal'] === 1;
    this.plenarioPrincipalFixo = o['plenario_principal_fixo'] === true || o['plenario_principal_fixo'] === 1;
    this.participaEscala = o['participa_escala'] === true || o['participa_escala'] === 1;
  }

  // Foto exibida: preview da nova foto > foto atual > '' (mostra iniciais)
  fotoUrl = computed(() => {
    if (this.fotoPreview()) return this.fotoPreview();
    const u = this.op()?.['foto_url'];
    if (!u) return '';
    return u.startsWith('http') ? u : environment.apiBaseUrl + u;
  });

  turnoLabel = computed(() => {
    const t = this.op()?.['turno'];
    return t === 'V' ? 'Vespertino' : t === 'M' ? 'Matutino' : '—';
  });

  cargaLabel = computed(() => {
    const c = this.op()?.['carga_horaria'];
    return c != null ? c + 'H' : '—';
  });

  horarioLabel = computed(() => {
    const i = this.op()?.['horario_trabalho_inicio'];
    const f = this.op()?.['horario_trabalho_fim'];
    if (i && f) return `${i} às ${f}`;
    if (i || f) return `${i || '—'} às ${f || '—'}`;
    return '—';
  });

  entrarEdicao(): void {
    const o = this.op(); if (o) this.popularCampos(o);
    this.foto = null; this.fotoPreview.set(''); this.errorMsg.set('');
    this.editing.set(true);
  }

  cancelar(): void {
    const o = this.op(); if (o) this.popularCampos(o);
    this.foto = null; this.fotoPreview.set(''); this.errorMsg.set('');
    this.editing.set(false);
  }

  onPlenarioChange(): void {
    if (!this.plenarioPrincipal) this.plenarioPrincipalFixo = false;
  }

  /** Máscara HH:MM: aceita só dígitos (máx. 4) e insere ':' automaticamente após o 2º. */
  onHoraInput(event: Event, campo: 'inicio' | 'fim'): void {
    const input = event.target as HTMLInputElement;
    const apagando = (event as InputEvent).inputType?.startsWith('delete') ?? false;
    const dig = input.value.replace(/\D/g, '').slice(0, 4);
    let masked: string;
    if (dig.length > 2) masked = dig.slice(0, 2) + ':' + dig.slice(2);
    else if (dig.length === 2 && !apagando) masked = dig + ':';
    else masked = dig;
    input.value = masked;
    if (campo === 'inicio') this.horaInicio = masked; else this.horaFim = masked;
  }

  onFileSelect(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0] || null;
    this.foto = file;
    if (file) {
      const reader = new FileReader();
      reader.onload = () => this.fotoPreview.set(reader.result as string);
      reader.readAsDataURL(file);
    } else {
      this.fotoPreview.set('');
    }
  }

  salvar(): void {
    this.errorMsg.set('');
    if (!this.nomeCompleto.trim() || !this.nomeExibicao.trim() || !this.email.trim()) {
      this.errorMsg.set('Nome, Nome de Chamada e E-mail são obrigatórios.'); return;
    }
    if (this.horaInicio && !this.horaRe.test(this.horaInicio)) { this.errorMsg.set('Horário de início inválido (use HH:MM).'); return; }
    if (this.horaFim && !this.horaRe.test(this.horaFim)) { this.errorMsg.set('Horário de fim inválido (use HH:MM).'); return; }

    this.saving.set(true);
    const fd = new FormData();
    fd.append('nome_completo', this.nomeCompleto.trim());
    fd.append('nome_exibicao', this.nomeExibicao.trim());
    fd.append('email', this.email.trim());
    fd.append('turno', this.turno);
    fd.append('carga_horaria', this.cargaHoraria);
    fd.append('horario_trabalho_inicio', this.horaInicio.trim());
    fd.append('horario_trabalho_fim', this.horaFim.trim());
    fd.append('plenario_principal', String(this.plenarioPrincipal));
    fd.append('plenario_principal_fixo', String(this.plenarioPrincipal && this.plenarioPrincipalFixo));
    fd.append('participa_escala', String(this.participaEscala));
    if (this.foto) fd.append('foto', this.foto);

    this.api.postForm<any>(`/api/admin/operador/${this.id}/atualizar`, fd).subscribe({
      next: res => {
        this.saving.set(false);
        const o = res?.operador ?? res;
        this.op.set(o); this.popularCampos(o);
        this.foto = null; this.fotoPreview.set('');
        this.editing.set(false);
      },
      error: err => {
        this.saving.set(false);
        const e = err?.error;
        this.errorMsg.set(e?.message || (err?.status === 409 ? 'E-mail já cadastrado para outro usuário.' : 'Erro ao salvar.'));
      },
    });
  }

  voltar(): void {
    this.router.navigate(['/admin/gestao-pessoas']);
  }
}
