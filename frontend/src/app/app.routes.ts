import { Routes } from '@angular/router';
import { authGuard, adminGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login/login.component').then(m => m.LoginComponent) },
  {
    path: '',
    loadComponent: () => import('./layout/main-layout.component').then(m => m.MainLayoutComponent),
    canActivate: [authGuard],
    children: [
      // ── Operador ──
      { path: 'home', loadComponent: () => import('./pages/home/home.component').then(m => m.HomeComponent) },
      { path: 'checklist', loadComponent: () => import('./pages/home/checklist-wizard.component').then(m => m.ChecklistWizardComponent) },
      { path: 'checklist/edit', loadComponent: () => import('./pages/home/checklist-wizard.component').then(m => m.ChecklistWizardComponent) },
      { path: 'operacao', loadComponent: () => import('./pages/home/operacao-form.component').then(m => m.OperacaoFormComponent) },
      { path: 'operacao/edit', loadComponent: () => import('./pages/home/operacao-form.component').then(m => m.OperacaoFormComponent) },
      { path: 'anormalidade', loadComponent: () => import('./pages/home/anormalidade-form.component').then(m => m.AnormalidadeFormComponent) },
      { path: 'anormalidade/edit', loadComponent: () => import('./pages/home/anormalidade-form.component').then(m => m.AnormalidadeFormComponent) },
      { path: 'anormalidade/detalhe', loadComponent: () => import('./pages/admin/anormalidade-detalhe.component').then(m => m.AnormalidadeDetalheComponent) },

      // ── Admin ──
      { path: 'admin', canActivate: [adminGuard], children: [
        { path: '', loadComponent: () => import('./pages/admin/admin-dashboard.component').then(m => m.AdminDashboardComponent) },
        { path: 'operacoes', loadComponent: () => import('./pages/admin/admin-operacoes.component').then(m => m.AdminOperacoesComponent) },
        { path: 'novo-operador', loadComponent: () => import('./pages/admin/admin-novo-operador.component').then(m => m.AdminNovoOperadorComponent) },
        { path: 'form-edit', loadComponent: () => import('./pages/admin/admin-form-edit.component').then(m => m.AdminFormEditComponent) },
        { path: 'checklist/detalhe', loadComponent: () => import('./pages/admin/checklist-detalhe.component').then(m => m.ChecklistDetalheComponent) },
        { path: 'operacao/detalhe', loadComponent: () => import('./pages/admin/operacao-detalhe.component').then(m => m.OperacaoDetalheComponent) },
        { path: 'anormalidade/detalhe', loadComponent: () => import('./pages/admin/anormalidade-detalhe.component').then(m => m.AnormalidadeDetalheComponent) },
      ]},
      { path: '', redirectTo: 'home', pathMatch: 'full' },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
