import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatTabsModule } from '@angular/material/tabs';

import { errorMessage } from '../../core/api/requests';
import { Page } from '../../shared/models';
import { formatDateTime, formatMoney } from '../../shared/ui/format';
import { statusLabel } from '../../shared/ui/status-chip.component';
import { HistoryService } from './data/history.service';

const SIZE = 20;

const CHANNELS: Record<string, string> = {
  MPESA_PAYBILL: 'M-Pesa Paybill',
  MPESA_STK: 'M-Pesa prompt',
  CASH: 'Cash',
  BANK: 'Bank',
  CARD: 'Card',
};

const ENTRY_TYPES: Record<string, string> = {
  PAYMENT: 'Payment',
  RENEWAL_CHARGE: 'Renewal',
  PRORATION_CHARGE: 'Upgrade charge',
  POSTPAID_CHARGE: 'Monthly charge',
  ADJUSTMENT: 'Adjustment',
  CREDIT_NOTE: 'Credit note',
  REFUND: 'Refund',
  REVERSAL: 'Reversal',
};

/**
 * History (docs/09 portal: payment history, receipts): payments, receipts and the wallet statement,
 * newest first, a page at a time from the server. Amounts carry their sign and are never coloured.
 */
@Component({
  selector: 'app-history-page',
  imports: [NgTemplateOutlet, MatButtonModule, MatTabsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './history-page.component.html',
  styleUrl: './history-page.component.scss',
})
export class HistoryPageComponent {
  private readonly history = inject(HistoryService);

  protected readonly paymentsPage = signal(0);
  protected readonly receiptsPage = signal(0);
  protected readonly statementPage = signal(0);

  protected readonly payments = rxResource({
    params: () => this.paymentsPage(),
    stream: ({ params }) => this.history.payments(params, SIZE),
  });
  protected readonly receipts = rxResource({
    params: () => this.receiptsPage(),
    stream: ({ params }) => this.history.receipts(params, SIZE),
  });
  protected readonly statement = rxResource({
    params: () => this.statementPage(),
    stream: ({ params }) => this.history.statement(params, SIZE),
  });

  protected readonly paymentsPages = computed(() => pages(this.payments.value()));
  protected readonly receiptsPages = computed(() => pages(this.receipts.value()));
  protected readonly statementPages = computed(() => pages(this.statement.value()));

  protected readonly money = formatMoney;
  protected readonly date = formatDateTime;
  protected readonly status = statusLabel;
  protected readonly message = (e: unknown) =>
    errorMessage(e, 'This could not be loaded. Try again.');

  protected channel(code: string | undefined): string {
    return (code && CHANNELS[code]) || statusLabel(code);
  }

  protected entryType(code: string | undefined): string {
    return (code && ENTRY_TYPES[code]) || statusLabel(code);
  }
}

function pages(page: Page<unknown> | undefined): number {
  return page ? Math.max(1, Math.ceil(page.total / SIZE)) : 1;
}
