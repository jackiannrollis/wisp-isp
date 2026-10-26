import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { Observable, catchError, of } from 'rxjs';

import { errorMessage, newIdempotencyKey, shillingsToMinor } from '../../../core/api/requests';
import { formatMoney } from '../../../shared/ui/format';
import { WalletsService } from '../../billing/data/wallets.service';
import { RoutersService } from '../../network/data/routers.service';
import { PackagesService } from '../../packages/data/packages.service';
import { PaymentsService } from '../../payments/data/payments.service';
import { Line, LinesService, PaidOfflineReport } from '../../subscriptions/data/lines.service';
import { Customer, CustomersService } from '../data/customers.service';

const COMMON = [
  ReactiveFormsModule,
  MatDialogModule,
  MatButtonModule,
  MatFormFieldModule,
  MatInputModule,
  MatSelectModule,
  MatRadioModule,
];

const STYLES = `
  form { display: flex; flex-direction: column; gap: 0.25rem; min-width: min(26rem, 80vw); padding-top: 0.5rem; }
  .row { display: flex; gap: 0.75rem; }
  .row > * { flex: 1; }
  .error { color: var(--mat-sys-error); margin: 0; }
  .hint { color: var(--mat-sys-on-surface-variant); margin: 0 0 0.75rem; }
`;

/** Runs a request, closes the dialog with its result, or keeps the dialog open with the error. */
abstract class SubmittingDialog<R> {
  protected readonly ref = inject<MatDialogRef<unknown, R>>(MatDialogRef);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected send(request: Observable<R>): void {
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: (result) => this.ref.close(result),
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}

/** Create or edit a customer's profile; the number is assigned by the platform and never changes (T2). */
@Component({
  selector: 'app-customer-form-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>{{ data.customer ? 'Edit ' + data.customer.number : 'New customer' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" id="customer-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput formControlName="name" maxlength="200" />
        </mat-form-field>
        <div class="row">
          <mat-form-field appearance="outline">
            <mat-label>Phone</mat-label>
            <input matInput formControlName="phone" inputmode="tel" />
            <mat-hint>0712 345 678 or +254712345678</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Email</mat-label>
            <input matInput type="email" formControlName="email" />
          </mat-form-field>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Address</mat-label>
          <input matInput formControlName="address" maxlength="500" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Installation location</mat-label>
          <input matInput formControlName="installationLocation" maxlength="500" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Old account reference</mat-label>
          <input matInput formControlName="legacyAccountRef" maxlength="32" />
          <mat-hint
            >What this customer typed as the M-Pesa account in your old system; still
            matches</mat-hint
          >
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button
        mat-flat-button
        type="submit"
        form="customer-form"
        [disabled]="form.invalid || busy()"
      >
        Save
      </button>
    </mat-dialog-actions>
  `,
})
export class CustomerFormDialogComponent extends SubmittingDialog<Customer> {
  protected readonly data = inject<{ customer?: Customer }>(MAT_DIALOG_DATA);
  private readonly customers = inject(CustomersService);
  protected readonly form = inject(NonNullableFormBuilder).group({
    name: [this.data.customer?.name ?? '', [Validators.required, Validators.maxLength(200)]],
    phone: [this.data.customer?.phone ?? '', Validators.required],
    email: [this.data.customer?.email ?? '', Validators.email],
    address: [this.data.customer?.address ?? ''],
    installationLocation: [this.data.customer?.installationLocation ?? ''],
    legacyAccountRef: [this.data.customer?.legacyAccountRef ?? ''],
  });

  protected save(): void {
    const value = this.form.getRawValue();
    const request = {
      name: value.name.trim(),
      phone: value.phone.trim(),
      email: value.email.trim() || undefined,
      address: value.address.trim() || undefined,
      installationLocation: value.installationLocation.trim() || undefined,
      lat: this.data.customer?.lat,
      lng: this.data.customer?.lng,
      legacyAccountRef: value.legacyAccountRef.trim() || undefined,
    };
    const id = this.data.customer?.id;
    this.send(id ? this.customers.update(id, request) : this.customers.create(request));
  }
}

/** Open a PPPoE line: a package and a router; the first password is then shown once (ADR 0009). */
@Component({
  selector: 'app-open-line-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Add a line</h2>
    <mat-dialog-content>
      <p class="hint">
        The line starts as Pending; the first payment that covers the package activates it.
      </p>
      <form [formGroup]="form" id="line-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Package</mat-label>
          <mat-select formControlName="packageId">
            @for (p of packages(); track p.id) {
              <mat-option [value]="p.id">{{ p.name }} · {{ money(p.price) }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Router</mat-label>
          <mat-select formControlName="routerId">
            @for (r of routers(); track r.id) {
              <mat-option [value]="r.id">{{ r.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="line-form" [disabled]="form.invalid || busy()">
        Add line
      </button>
    </mat-dialog-actions>
  `,
})
export class OpenLineDialogComponent extends SubmittingDialog<unknown> {
  private readonly data = inject<{ customerId: string }>(MAT_DIALOG_DATA);
  private readonly lines = inject(LinesService);
  protected readonly money = formatMoney;
  private readonly allPackages = toSignal(
    inject(PackagesService)
      .all()
      .pipe(catchError(() => of([]))),
    {
      initialValue: [],
    },
  );
  /** Only active PPPoE packages can be sold on a line. */
  protected readonly packages = computed(() =>
    this.allPackages().filter((p) => p.active && p.type === 'PPPOE'),
  );
  protected readonly routers = toSignal(
    inject(RoutersService)
      .all()
      .pipe(catchError(() => of([]))),
    {
      initialValue: [],
    },
  );
  protected readonly form = inject(NonNullableFormBuilder).group({
    packageId: ['', Validators.required],
    routerId: ['', Validators.required],
  });

  protected save(): void {
    const { packageId, routerId } = this.form.getRawValue();
    this.send(this.lines.open(this.data.customerId, packageId, routerId));
  }
}

/** Pause until a date (S7): the clock stops and the line is restricted, not rejected, until then. */
@Component({
  selector: 'app-pause-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Pause {{ data.line.pppoeUsername }}</h2>
    <mat-dialog-content>
      <p class="hint">
        The remaining time is kept and added back on resume. The line resumes by itself at this
        time.
      </p>
      <form [formGroup]="form" id="pause-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Pause until</mat-label>
          <input matInput type="datetime-local" formControlName="until" [min]="now" />
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="pause-form" [disabled]="form.invalid || busy()">
        Pause
      </button>
    </mat-dialog-actions>
  `,
})
export class PauseDialogComponent extends SubmittingDialog<Line> {
  protected readonly data = inject<{ line: Line }>(MAT_DIALOG_DATA);
  private readonly lines = inject(LinesService);
  private readonly key = newIdempotencyKey();
  protected readonly now = localInput(new Date());
  protected readonly form = inject(NonNullableFormBuilder).group({
    until: [localInput(new Date(Date.now() + 7 * 86_400_000)), Validators.required],
  });

  protected save(): void {
    const until = new Date(this.form.getRawValue().until);
    this.send(this.lines.pause(this.data.line.id ?? '', until.toISOString(), this.key));
  }
}

/** Change package: now (upgrades only, prorated, CoA, S10) or at the next renewal. */
@Component({
  selector: 'app-change-package-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Change package of {{ data.line.pppoeUsername }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" id="package-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>New package</mat-label>
          <mat-select formControlName="packageId">
            @for (p of packages(); track p.id) {
              <mat-option [value]="p.id">{{ p.name }} · {{ money(p.price) }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-radio-group formControlName="timing" aria-label="When">
          <mat-radio-button value="NEXT_RENEWAL">At the next renewal</mat-radio-button>
          <mat-radio-button value="NOW"
            >Now (upgrades only; the difference is charged for the days left)</mat-radio-button
          >
        </mat-radio-group>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="package-form" [disabled]="form.invalid || busy()">
        Change package
      </button>
    </mat-dialog-actions>
  `,
})
export class ChangePackageDialogComponent extends SubmittingDialog<Line> {
  protected readonly data = inject<{ line: Line }>(MAT_DIALOG_DATA);
  private readonly lines = inject(LinesService);
  private readonly key = newIdempotencyKey();
  protected readonly money = formatMoney;
  private readonly allPackages = toSignal(
    inject(PackagesService)
      .all()
      .pipe(catchError(() => of([]))),
    {
      initialValue: [],
    },
  );
  protected readonly packages = computed(() =>
    this.allPackages().filter(
      (p) => p.active && p.type === 'PPPOE' && p.id !== this.data.line.packageId,
    ),
  );
  protected readonly form = inject(NonNullableFormBuilder).group({
    packageId: ['', Validators.required],
    timing: ['NEXT_RENEWAL' as 'NOW' | 'NEXT_RENEWAL', Validators.required],
  });

  protected save(): void {
    const { packageId, timing } = this.form.getRawValue();
    this.send(this.lines.changePackage(this.data.line.id ?? '', packageId, timing, this.key));
  }
}

/** Record a cash, bank or M-Pesa payment by hand (P5); billing credits the wallet from it (P4). */
@Component({
  selector: 'app-manual-payment-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Record a payment</h2>
    <mat-dialog-content>
      <form [formGroup]="form" id="payment-form" (ngSubmit)="save()">
        <div class="row">
          <mat-form-field appearance="outline">
            <mat-label>Amount (KES)</mat-label>
            <input matInput formControlName="amount" inputmode="decimal" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Channel</mat-label>
            <mat-select formControlName="channel">
              <mat-option value="CASH">Cash</mat-option>
              <mat-option value="BANK">Bank</mat-option>
              <mat-option value="MPESA_PAYBILL">M-Pesa (before Daraja is live)</mat-option>
            </mat-select>
          </mat-form-field>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Reference</mat-label>
          <input matInput formControlName="reference" maxlength="64" />
          <mat-hint>Receipt-book number, bank reference or M-Pesa code</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Paid by (optional)</mat-label>
          <input matInput formControlName="payerName" maxlength="200" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Note (optional)</mat-label>
          <input matInput formControlName="note" maxlength="500" />
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="payment-form" [disabled]="form.invalid || busy()">
        Record payment
      </button>
    </mat-dialog-actions>
  `,
})
export class ManualPaymentDialogComponent extends SubmittingDialog<unknown> {
  private readonly data = inject<{ customerId: string }>(MAT_DIALOG_DATA);
  private readonly payments = inject(PaymentsService);
  private readonly key = newIdempotencyKey();
  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.pattern(/^\s*[\d,]+(\.\d{1,2})?\s*$/)]],
    channel: ['CASH' as 'CASH' | 'BANK' | 'MPESA_PAYBILL', Validators.required],
    reference: ['', [Validators.required, Validators.maxLength(64)]],
    payerName: [''],
    note: [''],
  });

  protected save(): void {
    const value = this.form.getRawValue();
    const amountMinor = shillingsToMinor(value.amount);
    if (amountMinor === null || amountMinor <= 0) {
      this.error.set('Enter an amount in shillings, for example 2500 or 2500.50.');
      return;
    }
    this.send(
      this.payments.recordManual(
        {
          customerId: this.data.customerId,
          amountMinor,
          channel: value.channel,
          reference: value.reference.trim(),
          payerName: value.payerName.trim() || undefined,
          note: value.note.trim() || undefined,
        },
        this.key,
      ),
    );
  }
}

/** An M-Pesa STK Push to the customer's phone (or another); the payment arrives by callback. */
@Component({
  selector: 'app-stk-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Send an M-Pesa request</h2>
    <mat-dialog-content>
      <p class="hint">The customer gets a prompt on the phone and enters their M-Pesa PIN there.</p>
      <form [formGroup]="form" id="stk-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Amount (KES, whole shillings)</mat-label>
          <input matInput formControlName="amount" inputmode="numeric" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Phone (empty: the customer's own)</mat-label>
          <input matInput formControlName="phone" inputmode="tel" />
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="stk-form" [disabled]="form.invalid || busy()">
        Send request
      </button>
    </mat-dialog-actions>
  `,
})
export class StkDialogComponent extends SubmittingDialog<unknown> {
  private readonly data = inject<{ customerId: string; amountMinor?: number }>(MAT_DIALOG_DATA);
  private readonly payments = inject(PaymentsService);
  private readonly key = newIdempotencyKey();
  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: [
      this.data.amountMinor ? String(Math.ceil(this.data.amountMinor / 100)) : '',
      [Validators.required, Validators.pattern(/^\s*[\d,]+\s*$/)],
    ],
    phone: [''],
  });

  protected save(): void {
    const value = this.form.getRawValue();
    const amountMinor = shillingsToMinor(value.amount);
    if (amountMinor === null || amountMinor <= 0) {
      this.error.set('M-Pesa takes whole shillings, for example 2500.');
      return;
    }
    this.send(
      this.payments.stkPush(
        this.data.customerId,
        amountMinor,
        value.phone.trim() || null,
        this.key,
      ),
    );
  }
}

/** A wallet ADJUSTMENT with a reason (B1): positive credits (and renews like a payment), negative debits. */
@Component({
  selector: 'app-adjust-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Adjust wallet</h2>
    <mat-dialog-content>
      <p class="hint">
        A correction or a grant, kept in the ledger with your name and reason. Use a minus sign to
        debit. The customer gets no "payment received" message.
      </p>
      <form [formGroup]="form" id="adjust-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Amount (KES)</mat-label>
          <input matInput formControlName="amount" inputmode="decimal" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="2" formControlName="reason" maxlength="500"></textarea>
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="adjust-form" [disabled]="form.invalid || busy()">
        Post adjustment
      </button>
    </mat-dialog-actions>
  `,
})
export class AdjustDialogComponent extends SubmittingDialog<unknown> {
  private readonly data = inject<{ customerId: string }>(MAT_DIALOG_DATA);
  private readonly wallets = inject(WalletsService);
  private readonly key = newIdempotencyKey();
  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.pattern(/^\s*-?[\d,]+(\.\d{1,2})?\s*$/)]],
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });

  protected save(): void {
    const value = this.form.getRawValue();
    const amountMinor = shillingsToMinor(value.amount, true);
    if (amountMinor === null || amountMinor === 0) {
      this.error.set('Enter an amount in shillings, for example 500 or -500.');
      return;
    }
    this.send(
      this.wallets.adjust(this.data.customerId, amountMinor, value.reason.trim(), this.key),
    );
  }
}

/**
 * Support's "Paid but still offline" (ADR 0018): the customer says they paid and are still cut off. It
 * records an incident on the line, linked to the customer's latest payment, which Gate 1 counts as a
 * manual reconnect. It changes nothing on the line: fixing it is the next step.
 */
@Component({
  selector: 'app-paid-offline-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Paid but still offline</h2>
    <mat-dialog-content>
      <p class="hint">
        Records an incident on the line, linked to the customer's latest payment. It does not change
        the line; check its access and sessions next.
      </p>
      <form [formGroup]="form" id="paid-offline-form" (ngSubmit)="save()">
        @if (data.lines.length > 1) {
          <mat-form-field appearance="outline">
            <mat-label>Line</mat-label>
            <mat-select formControlName="lineId">
              @for (line of data.lines; track line.id) {
                <mat-option [value]="line.id">{{ line.pppoeUsername }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        } @else {
          <p>
            Line <strong>{{ data.lines[0]?.pppoeUsername }}</strong>
          </p>
        }
        <mat-form-field appearance="outline">
          <mat-label>What the customer said (optional)</mat-label>
          <textarea matInput formControlName="note" rows="3" maxlength="500"></textarea>
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button
        mat-flat-button
        type="submit"
        form="paid-offline-form"
        [disabled]="form.invalid || busy()"
      >
        Record incident
      </button>
    </mat-dialog-actions>
  `,
})
export class PaidOfflineDialogComponent extends SubmittingDialog<PaidOfflineReport> {
  protected readonly data = inject<{ lines: Line[] }>(MAT_DIALOG_DATA);
  private readonly lines = inject(LinesService);
  protected readonly form = inject(NonNullableFormBuilder).group({
    lineId: [this.data.lines[0]?.id ?? '', Validators.required],
    note: ['', Validators.maxLength(500)],
  });

  protected save(): void {
    const { lineId, note } = this.form.getRawValue();
    this.send(this.lines.reportPaidOffline(lineId, note.trim() || null));
  }
}

/** A Date as the value of a datetime-local input (local time, minutes). */
function localInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
