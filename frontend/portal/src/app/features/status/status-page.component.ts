import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { errorMessage } from '../../core/api/requests';
import { formatMoney } from '../../shared/ui/format';
import { LinesService } from './data/lines.service';
import { LineCardComponent } from './line-card.component';

/**
 * My internet (docs/09 portal: status, package, expiry, usage): one card per line, the wallet
 * balance, and a way to pay when a line is due or offered early renewal.
 */
@Component({
  selector: 'app-status-page',
  imports: [MatButtonModule, RouterLink, LineCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="page-title">My internet</h1>
    @if (data.error(); as e) {
      <section class="panel notice" role="alert">
        <p>{{ message(e) }}</p>
        <button mat-stroked-button type="button" (click)="data.reload()">Try again</button>
      </section>
    } @else if (data.value(); as d) {
      <section class="panel summary" aria-label="Wallet">
        <div>
          <span class="label">Wallet balance</span>
          <span class="amount">{{ money(d.balance) }}</span>
        </div>
        @if (payable()) {
          <a mat-flat-button routerLink="/pay">Pay {{ money(d.payAll) }}</a>
        }
      </section>
      @for (line of d.lines ?? []; track line.id) {
        <app-line-card [line]="line" />
      } @empty {
        <p class="muted">You have no internet lines yet. Your provider sets them up.</p>
      }
    } @else {
      <p class="muted">Loading…</p>
    }
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    .summary {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 1rem 1.25rem;
    }
    .summary div {
      display: flex;
      flex-direction: column;
    }
    .label {
      font: var(--mat-sys-label-large);
      color: var(--mat-sys-on-surface-variant);
    }
    .amount {
      font: var(--mat-sys-headline-small);
      font-weight: 600;
    }
    .notice {
      padding: 1rem 1.25rem;
    }
    .notice p {
      margin: 0 0 0.75rem;
    }
  `,
})
export class StatusPageComponent {
  private readonly lines = inject(LinesService);

  protected readonly data = rxResource({ stream: () => this.lines.lines() });
  /** Something to pay now: the API's pay-all covers every due line and every early-renewal offer. */
  protected readonly payable = computed(() => (this.data.value()?.payAll?.amount ?? 0) > 0);

  protected readonly money = formatMoney;
  protected readonly message = (e: unknown) =>
    errorMessage(e, 'Your lines could not be loaded. Try again.');
}
