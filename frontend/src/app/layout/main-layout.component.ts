import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from './header.component';
import { FooterComponent } from './footer.component';
import { ToastComponent } from '../shared/components/toast.component';

@Component({
  selector: 'app-main-layout',
  standalone: true,
  imports: [RouterOutlet, HeaderComponent, FooterComponent, ToastComponent],
  template: `
    <app-toast />
    <app-header />
    <main class="main-content">
      <router-outlet />
    </main>
    <app-footer />
  `,
})
export class MainLayoutComponent {}
