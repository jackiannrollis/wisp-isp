import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

import { errorMessage, shillingsToMinor } from '../../core/api/requests';
import { Package, PackageRequest, PackagesService } from './data/packages.service';
import { kbpsToMbps, mbpsToKbps, splitPeriod } from './package-format';

const MBPS = /^\s*\d{1,5}(\.\d{1,3})?\s*$/;
const SHILLINGS = /^\s*\d{1,9}(\.\d{1,2})?\s*$/;

/**
 * Create or revise a PPPoE prepaid package (v1). Speeds are typed in Mbit/s and stored in kbit/s
 * (N4); the price in shillings becomes minor units at the boundary (B2). A revision applies from each
 * line's next renewal; the cycle a customer already paid for keeps its terms (S6). Fair-use and cap
 * fields are not edited here yet (docs/09 step 8b) and are sent back unchanged.
 */
@Component({
  selector: 'app-package-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ data.pkg ? 'Edit ' + data.pkg.name : 'New package' }}</h2>
    <mat-dialog-content>
      @if (data.pkg) {
        <p class="hint">
          Lines on this package get the new terms at their next renewal; a cycle already paid for
          keeps what it was sold.
        </p>
      }
      <form [formGroup]="form" id="package-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput formControlName="name" maxlength="100" placeholder="Home 10" />
        </mat-form-field>
        <div class="two">
          <mat-form-field appearance="outline">
            <mat-label>Download</mat-label>
            <input matInput formControlName="download" inputmode="decimal" />
            <span matTextSuffix>Mbit/s</span>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Upload</mat-label>
            <input matInput formControlName="upload" inputmode="decimal" />
            <span matTextSuffix>Mbit/s</span>
          </mat-form-field>
        </div>
        <div class="two">
          <mat-form-field appearance="outline">
            <mat-label>Price</mat-label>
            <span matTextPrefix>KES&nbsp;</span>
            <input matInput formControlName="price" inputmode="decimal" />
          </mat-form-field>
          <div class="period">
            <mat-form-field appearance="outline">
              <mat-label>Period</mat-label>
              <input matInput type="number" min="1" formControlName="periodAmount" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Unit</mat-label>
              <mat-select formControlName="periodUnit">
                <mat-option value="days">days</mat-option>
                <mat-option value="hours">hours</mat-option>
              </mat-select>
            </mat-form-field>
          </div>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Grace days</mat-label>
          <input matInput type="number" min="0" max="365" formControlName="graceDays" />
          <mat-hint
            >Days online after expiry before restriction. Blank uses your account default.</mat-hint
          >
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="package-form" [disabled]="form.invalid || busy()">
        {{ data.pkg ? 'Save' : 'Create package' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    form {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      min-width: min(30rem, 80vw);
      padding-top: 0.5rem;
    }
    .two {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.75rem;
    }
    .period {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.5rem;
    }
    .hint {
      color: var(--mat-sys-on-surface-variant);
      margin: 0 0 0.5rem;
    }
    .error {
      color: var(--mat-sys-error);
      margin: 0;
    }
  `,
})
export class PackageDialogComponent {
  protected readonly data = inject<{ pkg: Package | null }>(MAT_DIALOG_DATA);
  private readonly packages = inject(PackagesService);
  private readonly ref = inject<MatDialogRef<PackageDialogComponent, Package>>(MatDialogRef);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly period = splitPeriod(this.data.pkg?.durationMinutes ?? 43_200);
  protected readonly form = inject(NonNullableFormBuilder).group({
    name: [this.data.pkg?.name ?? '', [Validators.required, Validators.maxLength(100)]],
    download: [
      kbpsToMbps(this.data.pkg?.downloadKbps),
      [Validators.required, Validators.pattern(MBPS)],
    ],
    upload: [
      kbpsToMbps(this.data.pkg?.uploadKbps),
      [Validators.required, Validators.pattern(MBPS)],
    ],
    price: [
      this.data.pkg?.price?.amount !== undefined ? String(this.data.pkg.price.amount / 100) : '',
      [Validators.required, Validators.pattern(SHILLINGS)],
    ],
    periodAmount: [this.period.amount, [Validators.required, Validators.min(1)]],
    periodUnit: [this.period.unit as 'days' | 'hours'],
    graceDays: [
      this.data.pkg?.graceDays ?? (null as number | null),
      [Validators.min(0), Validators.max(365)],
    ],
  });

  protected save(): void {
    const v = this.form.getRawValue();
    const downloadKbps = mbpsToKbps(v.download);
    const uploadKbps = mbpsToKbps(v.upload);
    const priceMinor = shillingsToMinor(v.price);
    if (!downloadKbps || !uploadKbps) {
      this.error.set('Speeds must be above zero.');
      return;
    }
    if (priceMinor === null || priceMinor <= 0) {
      this.error.set('The price must be above zero.');
      return;
    }
    const pkg = this.data.pkg;
    const request: PackageRequest = {
      name: v.name.trim(),
      downloadKbps,
      uploadKbps,
      durationMinutes: v.periodAmount * (v.periodUnit === 'days' ? 1440 : 60),
      price: { amount: priceMinor, currency: 'KES' },
      graceDays: v.graceDays === null || (v.graceDays as unknown) === '' ? undefined : v.graceDays,
      // Kept as they are until the fair-use screen exists (docs/09 step 8b).
      type: pkg?.type,
      billingMode: pkg?.billingMode,
      dataCapBytes: pkg?.dataCapBytes,
      fupBytes: pkg?.fupBytes,
      throttleDownloadKbps: pkg?.throttleDownloadKbps,
      throttleUploadKbps: pkg?.throttleUploadKbps,
      deviceLimit: pkg?.deviceLimit,
    };
    this.busy.set(true);
    this.error.set(null);
    const call = pkg ? this.packages.update(pkg.id ?? '', request) : this.packages.create(request);
    call.subscribe({
      next: (saved) => this.ref.close(saved),
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
