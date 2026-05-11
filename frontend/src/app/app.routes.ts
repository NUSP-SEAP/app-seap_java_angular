import { Routes } from '@angular/router';
import { authGuard, roleGuard, matchByRole, masterGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  { path: 'login', title: 'Login | Senado NUSP', loadComponent: () => import('./pages/login/login.component').then(m => m.LoginComponent) },
  { path: 'forgot-password', title: 'Esqueci a Senha | Senado NUSP', loadComponent: () => import('./pages/login/forgot-password.component').then(m => m.ForgotPasswordComponent) },
  { path: 'reset-password', title: 'Redefinir Senha | Senado NUSP', loadComponent: () => import('./pages/login/reset-password.component').then(m => m.ResetPasswordComponent) },
  {
    path: '',
    loadComponent: () => import('./layout/main-layout.component').then(m => m.MainLayoutComponent),
    canActivate: [authGuard],
    children: [
      // ── Operador ──
      { path: 'home', canActivate: [roleGuard], data: { roles: ['operador'] }, title: 'Home | Senado NUSP', loadComponent: () => import('./pages/home/home.component').then(m => m.HomeComponent) },
      { path: 'checklist', canActivate: [roleGuard], data: { roles: ['operador'] }, title: 'Verificação de Plenários', loadComponent: () => import('./pages/home/checklist-wizard.component').then(m => m.ChecklistWizardComponent) },
      { path: 'checklist/edit', canActivate: [roleGuard], data: { roles: ['operador'] }, title: 'Verificação de Plenários', loadComponent: () => import('./pages/home/checklist-wizard.component').then(m => m.ChecklistWizardComponent) },
      { path: 'operacao', canActivate: [roleGuard], data: { roles: ['operador'] }, title: 'Registro de Operação de Áudio', loadComponent: () => import('./pages/home/operacao-form.component').then(m => m.OperacaoFormComponent) },
      { path: 'operacao/edit', canActivate: [roleGuard], data: { roles: ['operador'] }, title: 'Registro de Operação de Áudio', loadComponent: () => import('./pages/home/operacao-form.component').then(m => m.OperacaoFormComponent) },
      { path: 'anormalidade', canActivate: [roleGuard], data: { roles: ['operador'] }, title: 'Registro de Anormalidade na Operação de Áudio', loadComponent: () => import('./pages/home/anormalidade-form.component').then(m => m.AnormalidadeFormComponent) },
      { path: 'anormalidade/edit', canActivate: [roleGuard], data: { roles: ['operador'] }, title: 'Registro de Anormalidade na Operação de Áudio', loadComponent: () => import('./pages/home/anormalidade-form.component').then(m => m.AnormalidadeFormComponent) },
      { path: 'anormalidade/detalhe', canActivate: [roleGuard], data: { roles: ['operador'] }, title: 'Detalhe da Anormalidade | Operador', loadComponent: () => import('./pages/admin/anormalidade-detalhe.component').then(m => m.AnormalidadeDetalheComponent) },
      { path: 'agenda', canActivate: [roleGuard], data: { roles: ['operador'] }, title: 'Agenda Legislativa | Senado NUSP', loadComponent: () => import('./pages/home/agenda-legislativa.component').then(m => m.AgendaLegislativaComponent) },

      // ── Técnico ──
      { path: 'tecnico', canActivate: [roleGuard], data: { roles: ['tecnico'] }, title: 'Home | Técnicos', loadComponent: () => import('./pages/tecnico/tecnico-home.component').then(m => m.TecnicoHomeComponent) },

      // ── Admin ──
      { path: 'admin', canActivate: [roleGuard], data: { roles: ['administrador'] }, children: [
        { path: '', title: 'Admin | Senado NUSP', loadComponent: () => import('./pages/admin/admin-dashboard.component').then(m => m.AdminDashboardComponent) },
        { path: 'operacoes', title: 'Operações de Áudio | Admin', loadComponent: () => import('./pages/admin/admin-operacoes.component').then(m => m.AdminOperacoesComponent) },
        { path: 'novo-operador', title: 'Novo Operador — Administração', loadComponent: () => import('./pages/admin/admin-novo-operador.component').then(m => m.AdminNovoOperadorComponent) },
        { path: 'novo-tecnico', title: 'Novo Técnico — Administração', loadComponent: () => import('./pages/admin/admin-novo-tecnico.component').then(m => m.AdminNovoTecnicoComponent) },
        { path: 'novo-admin', canActivate: [masterGuard], title: 'Novo Administrador — Administração', loadComponent: () => import('./pages/admin/admin-novo-admin.component').then(m => m.AdminNovoAdminComponent) },
        { path: 'escala', title: 'Escala Semanal | Admin', loadComponent: () => import('./pages/admin/admin-escala.component').then(m => m.AdminEscalaComponent) },
        { path: 'agenda', title: 'Agenda Legislativa | Admin', loadComponent: () => import('./pages/admin/admin-agenda.component').then(m => m.AdminAgendaComponent) },
        { path: 'form-edit', title: 'Edição de Formulários | Admin', loadComponent: () => import('./pages/admin/admin-form-edit.component').then(m => m.AdminFormEditComponent) },
        { path: 'checklist/detalhe', title: 'Detalhe do Checklist | Admin', loadComponent: () => import('./pages/admin/checklist-detalhe.component').then(m => m.ChecklistDetalheComponent) },
        { path: 'operacao/detalhe', title: 'Detalhe da Operação | Admin', loadComponent: () => import('./pages/admin/operacao-detalhe.component').then(m => m.OperacaoDetalheComponent) },
        { path: 'anormalidade/detalhe', title: 'Detalhe da Anormalidade | Admin', loadComponent: () => import('./pages/admin/anormalidade-detalhe.component').then(m => m.AnormalidadeDetalheComponent) },
      ]},

      // ── Redirects para a raiz, baseados no papel ──
      { path: '', redirectTo: 'admin',   pathMatch: 'full', canMatch: [matchByRole], data: { roles: ['administrador'] } },
      { path: '', redirectTo: 'tecnico', pathMatch: 'full', canMatch: [matchByRole], data: { roles: ['tecnico'] } },
      { path: '', redirectTo: 'home',    pathMatch: 'full' },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
