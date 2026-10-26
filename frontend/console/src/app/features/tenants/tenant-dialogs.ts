import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Observable } from 'rxjs';

import { errorMessage } from '../../core/api/requests';
import { cutoverUntil, MAX_CUTOVER_DAYS, toLocalInput } from '../../shared/format';
import { CreatedTenant, Tenant } from '../../shared/models';
import { TenantsService } from './tenants.service';

const COMMON = [
  ReactiveFormsModule,
  MatDialogModule,
  MatButtonModule,
  MatFormFieldModule,
  MatInputModule,
];

const STYLES = `
  form { display: flex; flex-direction: column; gap: 0.25rem; min-width: min(26rem, 100%); padding-top: 0.5rem; }
  .row { display: flex; gap: 0.75rem; }
  .row > * { flex: 1; }
  .error { color: var(--mat-sys-error); margin: 0; }
  .hint { color: var(--mat-sys-on-surface-variant); margin: 0 0 0.75rem; }
  .secret { font-family: ui-monospace, monospace; font-size: 1.1rem; letter-spacing: 0.06em; padding: 0.75rem;
    border-radius: var(--app-radius-s); background: var(--mat-sys-surface-container-high); word-break: break-all; }
  .facts { display: grid; grid-template-columns: max-content 1fr; gap: 0.25rem 1rem; margin: 0 0 0.75rem; }
  .facts dt { color: var(--mat-sys-on-surface-variant); }
  .facts dd { margin: 0; }
  .destructive { --mat-button-filled-container-color: var(--mat-sys-error); --mat-button-filled-label-text-color: var(--mat-sys-on-error); }
`;

/**
 * A new tenant in TRIAL with its Owner invited (docs/07 onboarding step 1). The code prefixes every
 * customer number and username and never changes (T2); the subdomain is the tenant's portal.
 */
@Component({
  selector: 'app-create-tenant-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>New tenant</h2>
    <mat-dialog-content>
      <p class="hint">
        The code prefixes every customer number and PPPoE username and never changes. The Owner gets
        a temporary password, shown once on the next screen, and sets up 2FA at the first sign-in.
      </p>
      <form [formGroup]="form" id="tenant-form" (ngSubmit)="save()">
        <div class="row">
          <mat-form-field appearance="outline">
            <mat-label>Code</mat-label>
            <input matInput formControlName="code" maxlength="4" autocapitalize="characters" />
            <mat-hint>2 to 4 letters, e.g. ACME</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Portal subdomain</mat-label>
            <input matInput formControlName="subdomain" maxlength="41" />
            <mat-hint>lower-case, e.g. acme</mat-hint>
          </mat-form-field>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput formControlName="name" maxlength="100" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Owner's name</mat-label>
          <input matInput formControlName="ownerName" maxlength="100" />
        </mat-form-field>
        <div class="row">
          <mat-form-field appearance="outline">
            <mat-label>Owner's email</mat-label>
            <input matInput type="email" formControlName="ownerEmail" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Owner's phone</mat-label>
            <input matInput formControlName="ownerPhone" inputmode="tel" />
            <mat-hint>Optional</mat-hint>
          </mat-form-field>
        </div>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="tenant-form" [disabled]="form.invalid || busy()">
        Create tenant
      </button>
    </mat-dialog-actions>
  `,
})
export class CreateTenantDialogComponent {
  private readonly tenants = inject(TenantsService);
  private readonly ref =
    inject<MatDialogRef<CreateTenantDialogComponent, CreatedTenant>>(MatDialogRef);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    code: ['', [Validators.required, Validators.pattern(/^\s*[A-Za-z]{2,4}\s*$/)]],
    subdomain: ['', [Validators.required, Validators.pattern(/^\s*[a-z0-9][a-z0-9-]{1,40}\s*$/)]],
    name: ['', [Validators.required, Validators.maxLength(100)]],
    ownerName: ['', [Validators.required, Validators.maxLength(100)]],
    ownerEmail: ['', [Validators.required, Validators.email]],
    ownerPhone: [''],
  });

  protected save(): void {
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.tenants
      .create({
        code: v.code.trim().toUpperCase(),
        subdomain: v.subdomain.trim(),
        name: v.name.trim(),
        ownerName: v.ownerName.trim(),
        ownerEmail: v.ownerEmail.trim(),
        ownerPhone: v.ownerPhone.trim() || undefined,
      })
      .subscribe({
        next: (created) => this.ref.close(created),
        error: (e: unknown) => {
          this.error.set(errorMessage(e));
          this.busy.set(false);
        },
      });
  }
}

export interface PasswordDialogData {
  title: string;
  message: string;
  email?: string;
  password: string;
  expiresAt?: string;
}

/** A temporary password, shown once: copy it into the password manager, then close. */
@Component({
  selector: 'app-password-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p class="hint">{{ data.message }}</p>
      <dl class="facts">
        @if (data.email) {
          <dt>Email</dt>
          <dd>{{ data.email }}</dd>
        }
        @if (data.expiresAt) {
          <dt>Valid until</dt>
          <dd>{{ data.expiresAt }}</dd>
        }
      </dl>
      <p class="secret" aria-label="Temporary password">{{ data.password }}</p>
      @if (copied()) {
        <p class="hint" role="status">Copied.</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" (click)="copy()">Copy</button>
      <button mat-flat-button type="button" mat-dialog-close>I have kept it</button>
    </mat-dialog-actions>
  `,
})
export class PasswordDialogComponent {
  protected readonly data = inject<PasswordDialogData>(MAT_DIALOG_DATA);
  protected readonly copied = signal(false);

  protected copy(): void {
    void navigator.clipboard?.writeText(this.data.password).then(() => this.copied.set(true));
  }
}

export interface ConfirmDialogData<T> {
  title: string;
  message: string;
  confirm: string;
  /** Ask for a reason (kept in both audit logs); without it the dialog only confirms. */
  reasonLabel?: string;
  destructive?: boolean;
  run: (reason: string) => Observable<T>;
}

/** Confirms an action, optionally with a reason, and runs it; errors stay in the dialog. */
@Component({
  selector: 'app-confirm-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p class="hint">{{ data.message }}</p>
      @if (data.reasonLabel) {
        <form (ngSubmit)="submit()" id="confirm-form">
          <mat-form-field appearance="outline">
            <mat-label>{{ data.reasonLabel }}</mat-label>
            <textarea matInput rows="2" [formControl]="reason" maxlength="500"></textarea>
          </mat-form-field>
        </form>
      }
      @if (error(); as error) {
        <p class="error" role="alert">{{ error }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button
        mat-flat-button
        type="button"
        [class.destructive]="data.destructive"
        [disabled]="busy() || (!!data.reasonLabel && reason.invalid)"
        (click)="submit()"
      >
        {{ data.confirm }}
      </button>
    </mat-dialog-actions>
  `,
})
export class ConfirmDialogComponent<T> {
  protected readonly data = inject<ConfirmDialogData<T>>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ConfirmDialogComponent<T>, T>>(MatDialogRef);
  protected readonly reason = inject(NonNullableFormBuilder).control('', [
    Validators.required,
    Validators.maxLength(500),
  ]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected submit(): void {
    this.busy.set(true);
    this.error.set(null);
    this.data.run(this.reason.value.trim()).subscribe({
      next: (result) => this.ref.close(result),
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}

/**
 * Opens (or moves the end of) a tenant's cutover window (ADR 0014): until then its Owner may import
 * lines already active with the expiry their customers paid for in the old system. Closes by itself.
 */
@Component({
  selector: 'app-cutover-window-dialog',
  imports: COMMON,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Cutover window for {{ data.tenant.name }}</h2>
    <mat-dialog-content>
      <p class="hint">
        Until this moment the Owner may import customers with the expiry they already paid for in
        their old system. At most {{ maxDays }} days ahead; it closes by itself.
      </p>
      <form [formGroup]="form" id="window-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Open until (your time)</mat-label>
          <input matInput type="datetime-local" formControlName="until" [min]="min" [max]="max" />
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="window-form" [disabled]="busy()">
        Open window
      </button>
    </mat-dialog-actions>
  `,
})
export class CutoverWindowDialogComponent {
  protected readonly data = inject<{ tenant: Tenant }>(MAT_DIALOG_DATA);
  private readonly tenants = inject(TenantsService);
  private readonly ref = inject<MatDialogRef<CutoverWindowDialogComponent, Tenant>>(MatDialogRef);
  protected readonly maxDays = MAX_CUTOVER_DAYS;
  private readonly now = new Date();
  protected readonly min = toLocalInput(this.now);
  protected readonly max = toLocalInput(
    new Date(this.now.getTime() + MAX_CUTOVER_DAYS * 24 * 60 * 60 * 1000 - 60 * 1000),
  );
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    until: [
      toLocalInput(new Date(this.now.getTime() + 7 * 24 * 60 * 60 * 1000)),
      Validators.required,
    ],
  });

  protected save(): void {
    const { until, problem } = cutoverUntil(this.form.getRawValue().until, new Date());
    if (problem || !until) {
      this.error.set(problem ?? 'Choose a date and time.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.tenants.openCutoverWindow(this.data.tenant.id ?? '', until).subscribe({
      next: (tenant) => this.ref.close(tenant),
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
