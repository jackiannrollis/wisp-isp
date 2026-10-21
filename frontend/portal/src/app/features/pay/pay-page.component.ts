import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';
import {
  IconAlertTriangle,
  IconCircleCheck,
  IconDeviceMobile,
  IconRouter,
  TablerIconComponent,
} from '@tabler/icons-angular';

import { errorMessage } from '../../core/api/requests';
import { AuthService } from '../../core/auth/auth.service';
import { PortalLine } from '../../shared/models';
import { formatMoney } from '../../shared/ui/format';
import { lineSummary } from '../../shared/ui/line-format';
import { StatusChipComponent } from '../../shared/ui/status-chip.component';
import { LinesService } from '../status/data/lines.service';
import { PayFlow, PayTarget } from './pay-flow.service';

/**
 * Pay (docs/06 Restricted mode): a one-tap M-Pesa prompt per line for what that line still needs, and
 * "pay all" for every due line and every early-renewal offer. The amounts are the API's (payToRenew,
 * payAll: price less the wallet); the page only labels and sends them.
 */
@Component({
  selector: 'app-pay-page',
  imports: [
    NgTemplateOutlet,
    MatButtonModule,
    MatProgressSpinnerModule,
    RouterLink,
    StatusChipComponent,
    TablerIconComponent,
  ],
  providers: [PayFlow],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pay-page.component.html',
  styleUrl: './pay-page.component.scss',
})
export class PayPageComponent {
  private readonly linesApi = inject(LinesService);
  protected readonly flow = inject(PayFlow);
  protected readonly customer = inject(AuthService).customer;

  protected readonly data = rxResource({ stream: () => this.linesApi.lines() });
  /** Lines the API says to pay now: due, or offered early renewal (THROTTLED or CAPPED by usage). */
  protected readonly payable = computed(() =>
    (this.data.value()?.lines ?? []).filter((l) => l.due || l.earlyRenewal),
  );
  protected readonly others = computed(() =>
    (this.data.value()?.lines ?? []).filter((l) => !l.due && !l.earlyRenewal),
  );
  protected readonly payAll = computed(() => this.data.value()?.payAll);
  protected readonly showPayAll = computed(
    () =>
      (this.payAll()?.amount ?? 0) > 0 &&
      this.payable().filter((l) => (l.payToRenew?.amount ?? 0) > 0).length > 1,
  );

  protected readonly icons = {
    phone: IconDeviceMobile,
    done: IconCircleCheck,
    router: IconRouter,
    warn: IconAlertTriangle,
  };
  protected readonly money = formatMoney;
  protected readonly summary = lineSummary;
  protected readonly message = (e: unknown) =>
    errorMessage(e, 'Your lines could not be loaded. Try again.');

  constructor() {
    // Once the money is in, show the lines as they are now (renewed, new expiry).
    effect(() => {
      const phase = this.flow.phase();
      if (phase === 'paid' || phase === 'restored' || phase === 'restart') {
        this.data.reload();
      }
    });
  }

  protected payLine(line: PortalLine): void {
    this.flow.pay({
      label: `${line.packageName} (${line.pppoeUsername})`,
      amount: line.payToRenew!,
      watch: limited(line) ? [line.id!] : [],
    });
  }

  protected payEverything(): void {
    const amount = this.payAll();
    if (!amount) {
      return;
    }
    const target: PayTarget = {
      label: 'all lines due',
      amount,
      watch: this.payable()
        .filter(limited)
        .map((l) => l.id!),
    };
    this.flow.pay(target);
  }

  protected canPay(line: PortalLine): boolean {
    return (line.payToRenew?.amount ?? 0) > 0;
  }
}

/** A line whose access is less than FULL now, so "Internet restored" has something to wait for. */
function limited(line: PortalLine): boolean {
  return line.accessProfile !== 'FULL';
}
