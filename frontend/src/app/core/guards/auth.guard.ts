import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Route, Router, UrlSegment } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { homeRouteForRole } from '../helpers/auth.helpers';

/** Garante apenas que o usuário está logado. */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isLoggedIn()) return true;
  router.navigate(['/login']);
  return false;
};

/**
 * Guard único para controle de acesso por papel.
 * Lê `data.roles: string[]` da rota. Administrador tem acesso universal.
 * Usuário sem permissão é redirecionado para a home do próprio papel.
 *
 * Uso: `{ path: 'home', canActivate: [roleGuard], data: { roles: ['operador'] } }`
 */
export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.isLoggedIn()) { router.navigate(['/login']); return false; }
  if (auth.isAdmin()) return true;

  const allowed = (route.data?.['roles'] as string[] | undefined) ?? [];
  const role = auth.role();
  if (role && allowed.includes(role)) return true;

  router.navigate([homeRouteForRole(role)]);
  return false;
};

/**
 * Versão CanMatchFn do roleGuard, para usar em rotas com `redirectTo`.
 * Lê `data.roles: string[]` da rota. Não navega — apenas devolve true/false.
 */
export const matchByRole: CanMatchFn = (route: Route, _segments: UrlSegment[]) => {
  const allowed = (route.data?.['roles'] as string[] | undefined) ?? [];
  const role = inject(AuthService).role();
  return !!role && allowed.includes(role);
};

/** Regra especial: só o admin master (criar admins). */
export const masterGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isLoggedIn() && auth.isAdmin() && auth.user()?.isMaster) return true;
  if (auth.isLoggedIn()) router.navigate([homeRouteForRole(auth.role())]);
  else router.navigate(['/login']);
  return false;
};
