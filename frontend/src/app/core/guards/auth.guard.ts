import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isLoggedIn()) return true;
  router.navigate(['/login']);
  return false;
};

export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isLoggedIn() && auth.isAdmin()) return true;
  if (auth.isLoggedIn()) router.navigate(['/home']);
  else router.navigate(['/login']);
  return false;
};

export const adminRedirectGuard: CanMatchFn = () => {
  return inject(AuthService).isAdmin();
};

export const masterGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isLoggedIn() && auth.isAdmin() && auth.user()?.isMaster) return true;
  if (auth.isLoggedIn()) router.navigate(['/admin']);
  else router.navigate(['/login']);
  return false;
};
