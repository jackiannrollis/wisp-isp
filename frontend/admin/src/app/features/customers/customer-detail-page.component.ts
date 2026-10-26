import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import {
  IconArchive,
  IconCash,
  IconDeviceMobileMessage,
  IconDotsVertical,
  IconKey,
  IconPencil,
  IconPlayerPause,
  IconPlayerPlay,
  IconPlugConnectedX,
  IconPlus,
  IconScale,
  IconSwitchHorizontal,
  IconBan,
  IconLockOpen,
  IconTrash,
  IconWifiOff,
  TablerIconComponent,
} from '@tabler/icons-angular';
import { Observable, catchError, of, timer } from 'rxjs';

import { newIdempotencyKey } from '../../core/api/requests';
import { PermissionService } from '../../core/permissions/permission.service';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import { ColumnDef, TableFetch } from '../../shared/data-table/data-table.types';
import { CellDirective } from '../../shared/data-table/cell.directive';
import {
  ReasonDialogComponent,
  ReasonDialogData,
} from '../../shared/dialogs/reason-dialog.component';
import {
  SecretDialogComponent,
  SecretDialogData,
} from '../../shared/dialogs/secret-dialog.component';
import { formatDateTime, formatMoney } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { StatusChipComponent, statusLabel } from '../../shared/ui/status-chip.component';
import { accessText, usageText } from '../subscriptions/line-format';
import { LedgerEntry, Receipt, WalletsService } from '../billing/data/wallets.service';
import { PackagesService } from '../packages/data/packages.service';
import { Payment, PaymentsService } from '../payments/data/payments.service';
import {
  Line,
  LineOpened,
  LinesService,
  PppoeCredential,
} from '../subscriptions/data/lines.service';
import { Customer, CustomersService } from './data/customers.service';
import {
  AdjustDialogComponent,
  ChangePackageDialogComponent,
  CustomerFormDialogComponent,
  ManualPaymentDialogComponent,
  OpenLineDialogComponent,
  PaidOfflineDialogComponent,
  PauseDialogComponent,
  StkDialogComponent,
} from './dialogs/customer-dialogs';

/** Billing works from the outbox; its credit and any renewal follow within a few seconds. */
const REFRESH_AFTER_MS = 3000;

interface LineAction {
  label: string;
  icon: typeof IconKey;
  run: () => void;
}

/**
 * One customer: profile, lines with their lifecycle actions (S7 to S10), and, for finance.read, the
 * wallet with its statement, payments and receipts. Every button is shown only to holders of the
 * permission it needs; the server checks again (A2).
 */
@Component({
  selector: 'app-customer-detail-page',
  imports: [
    MatButtonModule,
    MatMenuModule,
    MatSnackBarModule,
    PageHeaderComponent,
    MatTabsModule,
    TablerIconComponent,
    StatusChipComponent,
    DataTableComponent,
    CellDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './customer-detail-page.component.html',
  styleUrl: './customer-detail-page.component.scss',
})
export class CustomerDetailPageComponent {
  /** The route's `:id`. */
  readonly id = input.required<string>();

  private readonly customers = inject(CustomersService);
  private readonly lines = inject(LinesService);
  private readonly wallets = inject(WalletsService);
  private readonly payments = inject(PaymentsService);
  private readonly dialog = inject(MatDialog);
  private readonly permissions = inject(PermissionService);
  private readonly snack = inject(MatSnackBar);

  protected readonly icons = {
    edit: IconPencil,
    archive: IconArchive,
    add: IconPlus,
    more: IconDotsVertical,
    cash: IconCash,
    stk: IconDeviceMobileMessage,
    adjust: IconScale,
    offline: IconWifiOff,
  };
  protected readonly money = formatMoney;
  protected readonly date = formatDateTime;
  protected readonly label = statusLabel;
  protected readonly access = accessText;
  protected readonly usage = usageText;

  protected readonly can = computed(() => {
    const p = this.permissions.permissions();
    return {
      editCustomer: p.has('customers.write'),
      manageLines: p.has('subscriptions.write'),
      seeMoney: p.has('finance.read'),
      recordPayment: p.has('payments.record_manual'),
      stk: p.has('payments.stk'),
      adjust: p.has('billing.adjust'),
    };
  });

  protected readonly customer = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.customers.get(params),
  });
  protected readonly lineList = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.lines.forCustomer(params),
  });
  protected readonly wallet = rxResource({
    params: () => (this.can().seeMoney ? this.id() : undefined),
    stream: ({ params }) => this.wallets.wallet(params),
  });

  private readonly packages = toSignal(
    inject(PackagesService)
      .all()
      .pipe(catchError(() => of([]))),
    {
      initialValue: [],
    },
  );
  protected readonly packageNames = computed(
    () => new Map(this.packages().map((p) => [p.id ?? '', p.name ?? ''])),
  );

  // --- money tables, scoped to this customer

  protected readonly entryColumns: ColumnDef<LedgerEntry>[] = [
    { key: 'createdAt', header: 'When', value: (e) => formatDateTime(e.createdAt) },
    { key: 'type', header: 'Type', value: (e) => statusLabel(e.type) },
    { key: 'description', header: 'Description', value: (e) => e.description ?? e.reason },
    { key: 'amount', header: 'Amount', value: (e) => formatMoney(e.amount), align: 'end' },
    { key: 'balance', header: 'Balance', value: (e) => formatMoney(e.balanceAfter), align: 'end' },
  ];
  protected readonly entries: TableFetch<LedgerEntry> = (query) =>
    this.wallets.entries(this.id(), query);

  protected readonly paymentColumns: ColumnDef<Payment>[] = [
    {
      key: 'receivedAt',
      header: 'Received',
      value: (p) => formatDateTime(p.receivedAt),
      sortKey: 'receivedAt',
    },
    { key: 'channel', header: 'Channel', value: (p) => statusLabel(p.channel) },
    { key: 'reference', header: 'Reference', value: (p) => p.reference },
    { key: 'status', header: 'Status', value: (p) => statusLabel(p.status) },
    { key: 'amount', header: 'Amount', value: (p) => formatMoney(p.amount), align: 'end' },
  ];
  protected readonly paymentList: TableFetch<Payment> = (query) =>
    this.payments.list({ ...query, filters: { ...query.filters, customerId: this.id() } });

  protected readonly receiptColumns: ColumnDef<Receipt>[] = [
    { key: 'number', header: 'Receipt', value: (r) => r.number },
    { key: 'issuedAt', header: 'Issued', value: (r) => formatDateTime(r.issuedAt) },
    { key: 'amount', header: 'Amount', value: (r) => formatMoney(r.amount), align: 'end' },
  ];
  protected readonly receipts: TableFetch<Receipt> = (query) =>
    this.wallets.receipts(this.id(), query);

  // --- customer actions

  protected edit(customer: Customer): void {
    this.dialog
      .open(CustomerFormDialogComponent, { data: { customer } })
      .afterClosed()
      .subscribe((saved) => saved && this.customer.reload());
  }

  protected archive(customer: Customer): void {
    this.confirm({
      title: `Archive ${customer.number}?`,
      message:
        'An archived customer cannot get new lines. The number is never reused. Terminate any lines first if they should stop.',
      confirm: 'Archive',
      destructive: true,
      run: () => this.customers.archive(customer.id ?? ''),
    }).subscribe((done) => done && this.customer.reload());
  }

  protected addLine(): void {
    this.dialog
      .open(OpenLineDialogComponent, { data: { customerId: this.id() } })
      .afterClosed()
      .subscribe((opened: LineOpened | undefined) => {
        if (opened) {
          this.lineList.reload();
          this.showPassword(opened.subscription?.pppoeUsername, opened.pppoePassword, 'New line');
        }
      });
  }

  protected recordPayment(): void {
    this.afterMoney(
      this.dialog.open(ManualPaymentDialogComponent, { data: { customerId: this.id() } }),
    );
  }

  protected stkPush(): void {
    this.afterMoney(this.dialog.open(StkDialogComponent, { data: { customerId: this.id() } }));
  }

  protected adjust(): void {
    this.afterMoney(this.dialog.open(AdjustDialogComponent, { data: { customerId: this.id() } }));
  }

  /** The actions a line's stored state allows (docs/04 transitions); the server enforces them again. */
  protected lineActions(line: Line): LineAction[] {
    const id = line.id ?? '';
    if (line.state === 'PENDING' && this.can().editCustomer) {
      // Cancel before activation needs customers.write, not subscriptions.write (decisions.md 2026-10-21).
      const cancel: LineAction = {
        label: 'Cancel line',
        icon: IconTrash,
        run: () =>
          this.lineChange(
            line,
            'Cancel line',
            'The line was never paid. It closes for good; any money in the wallet stays there.',
            'Reason',
            (r, key) => this.lines.cancel(id, r, key),
            true,
            'Keep line',
          ),
      };
      return this.can().manageLines ? [...this.manageActions(line, id), cancel] : [cancel];
    }
    if (!this.can().manageLines || line.state === 'TERMINATED') {
      return [];
    }
    return this.manageActions(line, id);
  }

  /** What subscriptions.write allows for the line's stored state. */
  private manageActions(line: Line, id: string): LineAction[] {
    const actions: LineAction[] = [];
    if (line.state === 'ACTIVE') {
      actions.push({ label: 'Pause', icon: IconPlayerPause, run: () => this.pause(line) });
    }
    if (line.state === 'PAUSED') {
      actions.push({
        label: 'Resume',
        icon: IconPlayerPlay,
        run: () =>
          this.lineChange(
            line,
            'Resume',
            'The paused time is added back to the expiry.',
            null,
            (_, key) => this.lines.resume(id, key),
          ),
      });
    }
    if (line.state === 'ACTIVE' || line.state === 'PAUSED') {
      actions.push({
        label: 'Suspend',
        icon: IconBan,
        run: () =>
          this.lineChange(
            line,
            'Suspend',
            'The line is rejected at login until the suspension is lifted.',
            'Reason',
            (r, key) => this.lines.suspend(id, r, key),
          ),
      });
    }
    if (line.state === 'SUSPENDED') {
      actions.push({
        label: 'Lift suspension',
        icon: IconLockOpen,
        run: () =>
          this.lineChange(
            line,
            'Lift suspension',
            'Access follows the dates again; an expired line returns restricted.',
            'Reason',
            (r, key) => this.lines.liftSuspension(id, r, key),
          ),
      });
    }
    if (line.state !== 'SUSPENDED') {
      actions.push({
        label: 'Change package',
        icon: IconSwitchHorizontal,
        run: () => this.changePackage(line),
      });
    }
    actions.push({ label: 'New PPPoE password', icon: IconKey, run: () => this.newPassword(line) });
    if (line.state === 'ACTIVE') {
      actions.push({
        label: 'Disconnect sessions',
        icon: IconPlugConnectedX,
        run: () =>
          this.lineChange(
            line,
            'Disconnect',
            'Every live session of the line is dropped and redials with its current access. Within two hours after a payment this counts as a manual reconnect for Gate 1.',
            'Reason',
            (r, key) => this.lines.disconnect(id, r, key),
          ),
      });
    }
    if (line.state !== 'ACTIVE' && line.state !== 'SUSPENDED') {
      return actions;
    }
    actions.push({
      label: 'Terminate',
      icon: IconTrash,
      run: () =>
        this.lineChange(
          line,
          'Terminate',
          'The line stops for good and cannot be reactivated.',
          'Reason',
          (r, key) => this.lines.terminate(id, r, key),
          true,
        ),
    });
    return actions;
  }

  /** The lines a "Paid but still offline" report may name: those that can be online. */
  protected readonly reportableLines = computed(() =>
    (this.lineList.value() ?? []).filter((l) => l.state === 'ACTIVE'),
  );

  protected paidOffline(): void {
    this.dialog
      .open(PaidOfflineDialogComponent, { data: { lines: this.reportableLines() } })
      .afterClosed()
      .subscribe((report) => {
        if (report) {
          this.snack.open(
            `Incident recorded, linked to the payment of ${formatDateTime(report.paymentMatchedAt)}.`,
            undefined,
            { duration: 6000 },
          );
        }
      });
  }

  private pause(line: Line): void {
    this.dialog
      .open(PauseDialogComponent, { data: { line } })
      .afterClosed()
      .subscribe((done) => done && this.lineList.reload());
  }

  private changePackage(line: Line): void {
    this.dialog
      .open(ChangePackageDialogComponent, { data: { line } })
      .afterClosed()
      .subscribe((done) => done && this.lineList.reload());
  }

  private newPassword(line: Line): void {
    this.confirm<PppoeCredential>({
      title: `New PPPoE password for ${line.pppoeUsername}?`,
      message:
        'The old password stops working at the next login. The customer router must be given the new one.',
      confirm: 'Generate',
      run: () => this.lines.newPassword(line.id ?? ''),
    }).subscribe((credential) => {
      if (credential) {
        this.showPassword(credential.pppoeUsername, credential.pppoePassword, 'New PPPoE password');
      }
    });
  }

  private lineChange(
    line: Line,
    action: string,
    message: string,
    reasonLabel: string | null,
    run: (reason: string, key: string) => Observable<unknown>,
    destructive = false,
    dismiss?: string,
  ): void {
    const key = newIdempotencyKey();
    this.confirm({
      title: `${action} ${line.pppoeUsername}?`,
      message,
      confirm: action,
      reasonLabel: reasonLabel ?? undefined,
      destructive,
      dismiss,
      run: (reason) => run(reason, key),
    }).subscribe((done) => done && this.lineList.reload());
  }

  private confirm<T>(data: ReasonDialogData<T>): Observable<T | undefined> {
    return this.dialog
      .open<ReasonDialogComponent<T>, ReasonDialogData<T>, T>(ReasonDialogComponent, { data })
      .afterClosed();
  }

  private showPassword(
    username: string | undefined,
    password: string | undefined,
    title: string,
  ): void {
    this.dialog.open<SecretDialogComponent, SecretDialogData>(SecretDialogComponent, {
      disableClose: true,
      data: {
        title,
        message: 'Enter these on the customer router (PPPoE client).',
        rows: [
          { label: 'Username', value: username ?? '' },
          { label: 'Password', value: password ?? '', secret: true },
        ],
      },
    });
  }

  /** Bumped to make the money tables read again. */
  protected readonly moneyVersion = signal(0);

  /**
   * After a payment or adjustment: billing credits the wallet and may renew a line from the outbox a
   * moment later (P4, B5), so everything is read now and once more a few seconds later.
   */
  private afterMoney(ref: { afterClosed(): Observable<unknown> }): void {
    ref.afterClosed().subscribe((done) => {
      if (done) {
        this.reloadMoney();
        timer(REFRESH_AFTER_MS).subscribe(() => this.reloadMoney());
      }
    });
  }

  private reloadMoney(): void {
    this.wallet.reload();
    this.lineList.reload();
    this.moneyVersion.update((v) => v + 1);
  }
}
