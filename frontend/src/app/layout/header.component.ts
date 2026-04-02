import { Component, computed, inject } from '@angular/core';
import { AuthService } from '../core/services/auth.service';

@Component({
  selector: 'app-header',
  standalone: true,
  template: `
    <header class="site-header">
      <div class="site-header__inner">
        <div class="logo-container">
          <img class="site-logo" src="assets/imgs/header-senado.png" alt="Senado Federal">
        </div>
        @if (auth.isLoggedIn()) {
          <div class="site-header__right">
            <span class="user-greeting">{{ userName() }}</span>
            <button class="btn-logout" (click)="auth.logout()">Sair</button>
          </div>
        }
      </div>
    </header>
  `,
  styles: [`
    .site-header {
      background: linear-gradient(to bottom, var(--senado-azul) 86%, var(--senado-amarelo) 86% 93%, var(--senado-verde) 93%);
      height: 54px;
      position: relative;
    }
    .site-header__inner {
      display: flex;
      align-items: center;
      justify-content: space-between;
      height: 47px;
      padding: 0 24px;
    }
    .site-logo { width: 180px; height: 45px; }
    .site-header__right {
      display: flex;
      align-items: center;
      gap: 16px;
    }
    .user-greeting { color: #fff; font-size: 0.9rem; font-weight: 600; }
    .btn-logout {
      background: #7f1d1d;
      color: #fee2e2;
      border: 1px solid #fca5a5;
      border-radius: 999px;
      padding: 5px 18px;
      font-size: 0.8rem;
      font-weight: 600;
      cursor: pointer;
      &:hover { background: #991b1b; }
    }
  `],
})
export class HeaderComponent {
  auth = inject(AuthService);
  userName = computed(() => {
    const u = this.auth.user();
    return u?.nome || u?.name || u?.username || '';
  });
}
