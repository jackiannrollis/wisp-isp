import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Observable } from 'rxjs';

import { errorMessage } from '../../core/api/requests';

export interface ReasonDialogData<T> {
  title: string;
  message: string;
  confirm: string;
  /** Ask for a reason (kept in the audit log, A1); without it the dialog only confirms. */
  reasonLabel?: string;
  destructive?: boolean;
  /** Runs on confirm with the reason; the dialog closes with its result, or shows the error. */
  run: (reason: string) => Observable<T>;
}

/** Confirms an action, optionally with a reason, and runs it; errors stay in the dialog. */
@Component({
  selector: 'app-reason-dialog',
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (data.reasonLabel) {
        <mat-form-field appearance="outline" class="full">
          <mat-label>{{ data.reasonLabel }}</mat-label>
          <textarea matInput rows="3" [formControl]="reason" maxlength="500"></textarea>
        </mat-form-field>
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
  styles: `
    .full {
      width: 100%;
    }
    .error {
      color: var(--mat-sys-error);
    }
    .destructive {
      --mat-button-filled-container-color: var(--mat-sys-error);
      --mat-button-filled-label-text-color: var(--mat-sys-on-error);
    }
  `,
})
export class ReasonDialogComponent<T> {
  protected readonly data = inject<ReasonDialogData<T>>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ReasonDialogComponent<T>, T>>(MatDialogRef);
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
