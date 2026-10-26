import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { IconLogout, TablerIconComponent } from '@tabler/icons-angular';

import { AuthService } from '../auth/auth.service';

/**
 * The console's frame: a top bar that says plainly this is Corebalt's platform console, not an ISP's
 * admin app, its one section (tenants) and sign-out.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatButtonModule, TablerIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="bar">
      <a routerLink="/tenants" class="brand">
        <span class="mark" aria-hidden="true">C</span>
        <span>Corebalt <strong>platform console</strong></span>
      </a>
      <nav aria-label="Console">
        <a routerLink="/tenants" routerLinkActive="active">Tenants</a>
      </nav>
      <button mat-button type="button" class="out" (click)="signOut()">
        <tabler-icon [icon]="logoutIcon" [size]="18" /> Sign out
      </button>
    </header>
    <main class="content">
      <router-outlet />
    </main>
  `,
  styles: `
    :host {
      display: block;
      min-height: 100vh;
    }
    .bar {
      display: flex;
      align-items: center;
      gap: var(--app-space-5);
      padding: var(--app-space-3) var(--app-space-5);
      background: var(--mat-sys-surface);
      border-bottom: var(--app-border);
    }
    .brand {
      display: flex;
      align-items: center;
      gap: var(--app-space-2);
      color: inherit;
      text-decoration: none;
      font: var(--mat-sys-title-small);
    }
    .mark {
      display: grid;
      place-items: center;
      width: 28px;
      height: 28px;
      border-radius: var(--app-radius-s);
      background: var(--mat-sys-inverse-surface);
      color: var(--mat-sys-inverse-on-surface);
      font-weight: 700;
    }
    nav a {
      color: var(--mat-sys-on-surface-variant);
      text-decoration: none;
      font: var(--mat-sys-label-large);
      padding: var(--app-space-1) var(--app-space-2);
      border-radius: var(--app-radius-s);
    }
    nav a.active {
      color: var(--mat-sys-on-surface);
      background: var(--mat-sys-surface-container-high);
    }
    .out {
      margin-left: auto;
    }
    .content {
      max-width: 1100px;
      margin: 0 auto;
      padding: var(--app-space-5) var(--app-space-4);
    }
  `,
})
export class ShellComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly logoutIcon = IconLogout;

  protected async signOut(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/login');
  }
}
