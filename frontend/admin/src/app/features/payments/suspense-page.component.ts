import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { IconUserCheck } from '@tabler/icons-angular';

import { PermissionService } from '../../core/permissions/permission.service';
import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import { ColumnDef, RowAction, TableFetch } from '../../shared/data-table/data-table.types';
import { formatDateTime, formatMoney } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { AssignDialogComponent } from './assign-dialog.component';
import { Payment, PaymentsService, SuspenseItem } from './data/payments.service';

/**
 * The suspense queue (P3): payments no reference or plain number matched. Staff assign each one to a
 * customer by hand; a masked payer number only suggests a shortlist, it is never matched automatically.
 */
@Component({
  selector: 'app-suspense-page',
  imports: [DataTableComponent, CellDirective, PageHeaderComponent, MatSnackBarModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .key {
      font-weight: 600;
    }
    .amount {
      font-weight: 600;
    }
    .likely {
      color: var(--app-warning);
      font-weight: 600;
    }
  `,
  template: `
    <app-page-header
      title="Suspense"
      subtitle="Payments the platform could not match to a customer. Assign each one; the customer's wallet is credited at once."
    />
    <app-data-table
      tableId="suspense"
      exportName="suspense"
      emptyText="Nothing in suspense. Every payment is matched."
      [itemLabel]="['payment', 'payments']"
      [columns]="columns"
      [fetch]="fetch"
      [actions]="actions()"
      [rowSelect]="select()"
      [refresh]="version()"
    >
      <ng-template appCell="reference" let-row
        ><span class="key">{{ row.reference }}</span></ng-template
      >
      <ng-template appCell="amount" let-row
        ><span class="amount">{{ money(row.amount) }}</span></ng-template
      >
      <ng-template appCell="likely" let-row>
        @if (row.likelyPayers?.length) {
          <span class="likely">{{ likely(row) }}</span>
        } @else {
          —
        }
      </ng-template>
    </app-data-table>
  `,
})
export class SuspensePageComponent {
  private readonly payments = inject(PaymentsService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  private readonly permissions = inject(PermissionService);

  protected readonly money = formatMoney;
  protected readonly version = signal(0);
  protected readonly fetch: TableFetch<SuspenseItem> = (query) => this.payments.suspense(query);

  protected readonly columns: ColumnDef<SuspenseItem>[] = [
    { key: 'receivedAt', header: 'Received', value: (s) => formatDateTime(s.receivedAt) },
    { key: 'reference', header: 'Reference', value: (s) => s.reference },
    { key: 'accountRef', header: 'Account typed', value: (s) => s.accountRef },
    {
      key: 'payer',
      header: 'Payer',
      value: (s) => [s.payerName, s.payerNumber].filter(Boolean).join(' · '),
    },
    { key: 'likely', header: 'Likely payers', value: (s) => this.likely(s) },
    { key: 'amount', header: 'Amount', value: (s) => formatMoney(s.amount), align: 'end' },
  ];

  protected readonly actions = computed<RowAction<SuspenseItem>[]>(() =>
    this.permissions.permissions().has('payments.assign')
      ? [{ label: 'Assign to a customer', icon: IconUserCheck, run: (s) => this.assign(s) }]
      : [],
  );

  /** Assigning is the only thing to do with a row, so the whole row opens it. */
  protected readonly select = computed(() =>
    this.permissions.permissions().has('payments.assign')
      ? (item: SuspenseItem) => this.assign(item)
      : null,
  );

  protected likely(item: SuspenseItem): string {
    const payers = item.likelyPayers ?? [];
    if (payers.length === 0) {
      return '';
    }
    return payers.length === 1 ? (payers[0].number ?? '') : `${payers.length} customers`;
  }

  private assign(item: SuspenseItem): void {
    this.dialog
      .open(AssignDialogComponent, { data: { item } })
      .afterClosed()
      .subscribe((payment: Payment | undefined) => {
        if (payment) {
          this.snack.open(
            `${item.reference} assigned to ${payment.customerNumber ?? 'the customer'}.`,
            undefined,
            { duration: 4000 },
          );
          this.version.update((v) => v + 1);
        }
      });
  }
}
