import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { PortalLine } from '../../shared/models';
import { formatBytes, formatMoney } from '../../shared/ui/format';
import { allowance, allowanceText, lineSummary, speedText } from '../../shared/ui/line-format';
import { StatusChipComponent } from '../../shared/ui/status-chip.component';
import { LinesService } from './data/lines.service';
import { UsageBarsComponent } from './usage-bars.component';

/**
 * One line: package and speeds, status in words, usage against the allowance (N9), what the next
 * renewal costs, and Pay Now when the API says the line is due or offered early renewal.
 */
@Component({
  selector: 'app-line-card',
  imports: [MatButtonModule, RouterLink, StatusChipComponent, UsageBarsComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let l = line();
    <article class="panel card" [attr.aria-label]="'Line ' + l.pppoeUsername">
      <header>
        <div>
          <h2 class="package">{{ l.packageName }}</h2>
          <span class="muted">{{ speed() }}</span>
        </div>
        <app-status-chip [status]="l.displayStatus" />
      </header>
      <p class="summary">{{ summary() }}</p>

      @if (meter(); as m) {
        <div class="meter">
          <div class="meter-head">
            <span>{{ m.label }} this period</span>
            <span class="meter-value">{{ meterText() }}</span>
          </div>
          <div
            class="track"
            role="meter"
            [attr.aria-label]="m.label + ' used'"
            aria-valuemin="0"
            [attr.aria-valuemax]="m.limitBytes"
            [attr.aria-valuenow]="m.usedBytes"
            [attr.aria-valuetext]="meterText()"
          >
            <div class="fill" [class.reached]="m.reached" [style.width.%]="m.share * 100"></div>
          </div>
        </div>
      } @else if (l.usage?.usedBytes) {
        <p class="muted">Used this period: {{ bytes(l.usage.usedBytes) }}</p>
      }

      @if (l.earlyRenewal) {
        <div class="action">
          <div>
            <strong>Renew early to restore full speed</strong>
            <span class="muted"
              >The new period starts when this one ends, and your allowance resets now.</span
            >
          </div>
          <a mat-flat-button routerLink="/pay">Pay {{ money(l.payToRenew) }}</a>
        </div>
      } @else if (l.due && (l.payToRenew?.amount ?? 0) > 0) {
        <div class="action">
          <div>
            <strong>{{ l.nextPackageName }} for {{ money(l.renewalPrice) }}</strong>
            @if ((l.payToRenew?.amount ?? 0) < (l.renewalPrice?.amount ?? 0)) {
              <span class="muted">Your wallet covers part of it.</span>
            }
          </div>
          <a mat-flat-button routerLink="/pay">Pay {{ money(l.payToRenew) }}</a>
        </div>
      }

      <dl class="facts">
        <div>
          <dt>Account</dt>
          <dd>{{ l.pppoeUsername }}</dd>
        </div>
        <div>
          <dt>Next renewal</dt>
          <dd>{{ l.nextPackageName }} · {{ money(l.renewalPrice) }}</dd>
        </div>
      </dl>

      <button
        mat-button
        type="button"
        class="toggle"
        [attr.aria-expanded]="showDaily()"
        (click)="showDaily.set(!showDaily())"
      >
        {{ showDaily() ? 'Hide daily usage' : 'Show daily usage' }}
      </button>
      @if (showDaily()) {
        @if (daily.value(); as days) {
          <app-usage-bars [days]="days" />
        } @else if (daily.error()) {
          <p class="muted" role="alert">Daily usage could not be loaded.</p>
        } @else {
          <p class="muted">Loading…</p>
        }
      }
    </article>
  `,
  styles: `
    .card {
      padding: 1rem 1.25rem 0.5rem;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }
    header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
    }
    .package {
      font: var(--mat-sys-title-large);
      font-weight: 600;
      margin: 0;
    }
    .summary {
      margin: 0;
    }
    .meter-head {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      font: var(--mat-sys-label-large);
      color: var(--mat-sys-on-surface-variant);
      margin-bottom: 0.375rem;
    }
    .meter-value {
      color: var(--mat-sys-on-surface);
      font-weight: 600;
    }
    .track {
      height: 0.5rem;
      border-radius: 4px;
      background: var(--mat-sys-surface-container-highest);
      overflow: hidden;
    }
    .fill {
      height: 100%;
      border-radius: 4px;
      background: var(--mat-sys-primary);
    }
    .fill.reached {
      background: var(--app-warning);
    }
    .action {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.75rem 1rem;
      border-radius: var(--app-radius-m);
      background: var(--mat-sys-surface-container);
    }
    .action div {
      display: flex;
      flex-direction: column;
      gap: 0.125rem;
    }
    .action a {
      flex: none;
    }
    .facts {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem 1rem;
      margin: 0;
    }
    dt {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    dd {
      margin: 0;
      font-weight: 500;
      overflow-wrap: anywhere;
    }
    .toggle {
      align-self: flex-start;
    }
    @media (max-width: 480px) {
      .action {
        flex-direction: column;
        align-items: stretch;
      }
    }
  `,
})
export class LineCardComponent {
  private readonly lines = inject(LinesService);

  readonly line = input.required<PortalLine>();

  protected readonly showDaily = signal(false);
  protected readonly summary = computed(() => lineSummary(this.line()));
  protected readonly speed = computed(() => speedText(this.line()));
  protected readonly meter = computed(() => allowance(this.line().usage));
  protected readonly meterText = computed(() => {
    const m = this.meter();
    return m ? allowanceText(m) : '';
  });
  /** Loaded only when the customer opens it. */
  protected readonly daily = rxResource({
    params: () => (this.showDaily() ? this.line().id : undefined),
    stream: ({ params }) => this.lines.usage(params),
  });

  protected readonly money = formatMoney;
  protected readonly bytes = formatBytes;
}
