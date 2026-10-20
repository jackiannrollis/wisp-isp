import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import {
  ColumnDef,
  FilterDef,
  TableFetch,
  TableQuery,
} from '../../shared/data-table/data-table.types';
import { formatDateTime, formatMoney } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { StatusChipComponent, statusLabel } from '../../shared/ui/status-chip.component';
import { Payment, PaymentsService } from './data/payments.service';

const CHANNELS: Record<string, string> = {
  MPESA_PAYBILL: 'M-Pesa Paybill',
  MPESA_STK: 'M-Pesa request',
  CASH: 'Cash',
  BANK: 'Bank',
  CARD: 'Card',
};

/**
 * Every payment received (finance.read), newest first: M-Pesa callbacks, STK results and manual
 * entries alike (P4). Matched payments open their customer; unmatched ones are worked in Suspense.
 */
@Component({
  selector: 'app-payments-page',
  imports: [DataTableComponent, CellDirective, StatusChipComponent, PageHeaderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .key {
      font-weight: 600;
    }
    .amount {
      font-weight: 600;
    }
  `,
  template: `
    <app-page-header
      title="Payments"
      subtitle="Everything received, from M-Pesa and by hand. Manual payments are recorded on the customer's page."
    />
    <app-data-table
      tableId="payments"
      exportName="payments"
      emptyText="No payments match."
      [itemLabel]="['payment', 'payments']"
      [columns]="columns"
      [filters]="filters"
      [fetch]="fetch"
      [rowLink]="link"
      [defaultSort]="{ key: 'receivedAt', direction: 'desc' }"
    >
      <ng-template appCell="reference" let-row
        ><span class="key">{{ row.reference }}</span></ng-template
      >
      <ng-template appCell="amount" let-row
        ><span class="amount">{{ money(row.amount) }}</span></ng-template
      >
      <ng-template appCell="status" let-row><app-status-chip [status]="row.status" /></ng-template>
    </app-data-table>
  `,
})
export class PaymentsPageComponent {
  private readonly payments = inject(PaymentsService);
  protected readonly money = formatMoney;

  /** The period chip becomes received-time bounds in the browser's calendar. */
  protected readonly fetch: TableFetch<Payment> = (query) => this.payments.list(withPeriod(query));

  /** Only matched payments have a customer to open. */
  protected readonly link = (p: Payment) =>
    p.customerId ? `/customers/${p.customerId}` : '/payments/suspense';

  protected readonly columns: ColumnDef<Payment>[] = [
    {
      key: 'receivedAt',
      header: 'Received',
      value: (p) => formatDateTime(p.receivedAt),
      sortKey: 'receivedAt',
    },
    { key: 'reference', header: 'Reference', value: (p) => p.reference },
    {
      key: 'customer',
      header: 'Customer',
      value: (p) => (p.customerNumber ? `${p.customerNumber} · ${p.customerName ?? ''}` : '—'),
    },
    { key: 'channel', header: 'Channel', value: (p) => CHANNELS[p.channel ?? ''] ?? p.channel },
    { key: 'status', header: 'Status', value: (p) => statusLabel(p.status) },
    { key: 'amount', header: 'Amount', value: (p) => formatMoney(p.amount), align: 'end' },
    {
      key: 'matchedAt',
      header: 'Matched',
      value: (p) => formatDateTime(p.matchedAt),
      hiddenByDefault: true,
    },
  ];

  protected readonly filters: FilterDef[] = [
    {
      key: 'period',
      label: 'Received',
      options: [
        { value: 'today', label: 'Today' },
        { value: 'week', label: 'Last 7 days' },
        { value: 'month', label: 'This month' },
      ],
    },
    {
      key: 'status',
      label: 'Status',
      options: [
        { value: 'MATCHED', label: 'Matched' },
        { value: 'UNMATCHED', label: 'Unmatched' },
        { value: 'REVERSED', label: 'Reversed' },
      ],
    },
    {
      key: 'channel',
      label: 'Channel',
      options: Object.entries(CHANNELS).map(([value, label]) => ({ value, label })),
    },
  ];
}

/** Replaces the `period` filter with `receivedFrom`, from the start of the chosen local day or month. */
export function withPeriod(query: TableQuery, now = new Date()): TableQuery {
  const { period, ...filters } = query.filters;
  if (!period) {
    return query;
  }
  const from = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (period === 'week') {
    from.setDate(from.getDate() - 6);
  } else if (period === 'month') {
    from.setDate(1);
  }
  return { ...query, filters: { ...filters, receivedFrom: from.toISOString() } };
}
