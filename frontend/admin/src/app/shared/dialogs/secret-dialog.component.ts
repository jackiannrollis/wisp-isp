import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { IconCheck, IconCopy, TablerIconComponent } from '@tabler/icons-angular';

export interface SecretDialogData {
  title: string;
  message: string;
  rows: { label: string; value: string; secret?: boolean }[];
}

/**
 * Shows a new password exactly once (T4, ADR 0009): it can never be read again, only regenerated.
 * Closing needs an explicit click, so the value is not lost to a stray click outside.
 */
@Component({
  selector: 'app-secret-dialog',
  imports: [MatDialogModule, MatButtonModule, TablerIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      <dl>
        @for (row of data.rows; track row.label) {
          <dt>{{ row.label }}</dt>
          <dd [class.secret]="row.secret">
            <span>{{ row.value }}</span>
            <button
              mat-icon-button
              type="button"
              [attr.aria-label]="'Copy ' + row.label"
              (click)="copy(row.label, row.value)"
            >
              <tabler-icon [icon]="copied() === row.label ? icons.check : icons.copy" [size]="18" />
            </button>
          </dd>
        }
      </dl>
      <p class="warning">This is the only time it is shown. Write it down or copy it now.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-flat-button type="button" mat-dialog-close>I have it</button>
    </mat-dialog-actions>
  `,
  styles: `
    dl {
      margin: 0;
    }
    dt {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
      margin-top: 0.5rem;
    }
    dd {
      margin: 0;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
    }
    .secret span {
      font-family: ui-monospace, monospace;
      font-size: 1.125rem;
      letter-spacing: 0.06em;
    }
    .warning {
      color: var(--app-warning);
      margin-top: 1rem;
    }
  `,
})
export class SecretDialogComponent {
  protected readonly data = inject<SecretDialogData>(MAT_DIALOG_DATA);
  protected readonly icons = { copy: IconCopy, check: IconCheck };
  protected readonly copied = signal<string | null>(null);

  protected async copy(label: string, value: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(value);
      this.copied.set(label);
    } catch {
      // Clipboard blocked: the value stays on screen to copy by hand.
    }
  }
}
