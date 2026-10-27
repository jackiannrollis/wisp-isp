import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { errorMessage } from '../../core/api/requests';
import { AuthService } from '../../core/auth/auth.service';

/** The hand-off code in the address fragment the console opens (`#code=…`), or null. */
export function handoffCode(fragment: string): string | null {
  const match = /(?:^#|[#&])code=([A-Za-z0-9_-]{16,100})(?:&|$)/.exec(fragment);
  return match ? match[1] : null;
}

/**
 * Where the platform console sends Corebalt support (ADR 0017): it takes the single-use code from the
 * address fragment, removes it from the address bar and the history at once, exchanges it for the
 * impersonation's token (kept in memory only) and opens the dashboard as the impersonated member.
 */
@Component({
  selector: 'app-impersonate-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="handoff">
      @if (error(); as message) {
        <h1>Impersonation not started</h1>
        <p role="alert">{{ message }}</p>
        <p class="muted">Start it again from the platform console. You can close this tab.</p>
      } @else {
        <p class="muted" role="status">Starting the impersonation…</p>
      }
    </main>
  `,
  styles: `
    .handoff {
      max-width: 32rem;
      margin: 15vh auto 0;
      padding: 0 var(--app-space-4, 1rem);
    }
    h1 {
      font: var(--mat-sys-title-large);
    }
    .muted {
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class ImpersonatePageComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    const code = handoffCode(window.location.hash);
    // The code is single use, but it still leaves the address bar and the history entry before anything else.
    history.replaceState(history.state, '', window.location.pathname);
    if (!code) {
      this.error.set('This page only works from the link the platform console opens.');
      return;
    }
    this.auth.startImpersonation(code).then(
      () => void this.router.navigateByUrl('/dashboard'),
      (e: unknown) =>
        this.error.set(errorMessage(e, 'The hand-off link is used, expired or ended.')),
    );
  }
}

/** After End, the expiry or a refused token: the impersonated session is gone from this tab. */
@Component({
  selector: 'app-impersonation-ended-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="handoff">
      <h1>Impersonation ended</h1>
      <p>Nothing of the session is kept in this tab. You can close it.</p>
    </main>
  `,
  styles: `
    .handoff {
      max-width: 32rem;
      margin: 15vh auto 0;
      padding: 0 var(--app-space-4, 1rem);
    }
    h1 {
      font: var(--mat-sys-title-large);
    }
  `,
})
export class ImpersonationEndedPageComponent {}
