import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';

import { errorMessage } from '../../core/api/requests';
import { Placeholder, SettingsService, Template } from './data/settings.service';
import { templateLabel } from './template-labels';

/**
 * Edit one SMS template (M3): placeholders insert at the cursor, and the server renders a preview
 * with sample values and counts its SMS parts as you type. The server refuses any placeholder not
 * on its list, so the preview shows that error before saving.
 */
@Component({
  selector: 'app-template-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatTooltipModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ label.label }}</h2>
    <mat-dialog-content>
      <p class="help">
        {{ label.when }}.
        {{ data.template.immediate ? 'Sent at once.' : 'Sent in the sending window.' }}
      </p>
      <mat-form-field appearance="outline" class="body">
        <mat-label>Wording</mat-label>
        <textarea
          #area
          matInput
          rows="4"
          maxlength="480"
          [ngModel]="body()"
          (ngModelChange)="body.set($event)"
        ></textarea>
      </mat-form-field>
      <div class="chips" aria-label="Insert a placeholder">
        @for (p of data.placeholders; track p.name) {
          <button
            type="button"
            class="chip"
            [matTooltip]="'Sample: ' + p.sample"
            (click)="insert(p.name ?? '')"
          >
            {{ '{' + p.name + '}' }}
          </button>
        }
      </div>

      <section class="preview" aria-live="polite">
        <h3>Preview with sample values</h3>
        @if (preview(); as p) {
          @if (p.error) {
            <p class="error" role="alert">{{ p.error }}</p>
          } @else {
            <p class="sample">{{ p.view?.sample }}</p>
            <p class="meta" [class.warn]="(p.view?.segments ?? 1) > 1">
              {{ p.view?.length }} characters · {{ p.view?.segments }}
              {{ p.view?.segments === 1 ? 'SMS part' : 'SMS parts' }} ·
              {{
                p.view?.encoding === 'UCS-2'
                  ? 'special characters (70 per part)'
                  : 'plain text (160 per part)'
              }}
            </p>
          }
        }
      </section>
      @if (error(); as error) {
        <p class="error" role="alert">{{ error }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions>
      @if (data.template.custom) {
        <button mat-button type="button" [disabled]="busy()" (click)="reset()">
          Back to the default
        </button>
      }
      <span class="spacer"></span>
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button
        mat-flat-button
        type="button"
        [disabled]="busy() || !body().trim() || body() === data.template.body || !!preview()?.error"
        (click)="save()"
      >
        Save
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .help {
      margin: 0 0 0.75rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .body {
      width: 100%;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 0.375rem;
      margin: -0.5rem 0 1rem;
    }
    .chip {
      padding: 0.125rem 0.5rem;
      border: var(--app-border);
      border-radius: 1rem;
      background: var(--mat-sys-surface);
      color: var(--mat-sys-on-surface);
      font: var(--mat-sys-label-medium);
      font-family: ui-monospace, monospace;
      cursor: pointer;
    }
    .chip:hover {
      background: var(--mat-sys-surface-container);
    }
    .chip:focus-visible {
      outline: var(--app-focus);
    }
    .preview {
      padding: 0.75rem 1rem;
      border: var(--app-border);
      border-radius: var(--app-radius-s);
      background: var(--mat-sys-surface-container-low);
    }
    .preview h3 {
      margin: 0 0 0.375rem;
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .sample {
      margin: 0 0 0.5rem;
    }
    .meta {
      margin: 0;
      font: var(--mat-sys-body-small);
      color: var(--mat-sys-on-surface-variant);
    }
    .warn {
      color: var(--app-warning);
      font-weight: 600;
    }
    .error {
      color: var(--mat-sys-error);
      margin: 0.5rem 0 0;
    }
    .spacer {
      flex: 1;
    }
  `,
})
export class TemplateDialogComponent {
  protected readonly data = inject<{ template: Template; placeholders: Placeholder[] }>(
    MAT_DIALOG_DATA,
  );
  private readonly settings = inject(SettingsService);
  private readonly ref = inject<MatDialogRef<TemplateDialogComponent, Template>>(MatDialogRef);
  private readonly area = viewChild.required<ElementRef<HTMLTextAreaElement>>('area');

  protected readonly label = templateLabel(this.data.template.key);
  protected readonly body = signal(this.data.template.body ?? '');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** The server's rendering of the draft; its validation error stands in for the preview. */
  protected readonly preview = toSignal(
    toObservable(this.body).pipe(
      debounceTime(300),
      map((b) => b.trim()),
      distinctUntilChanged(),
      switchMap((b) =>
        b
          ? this.settings.preview(this.data.template.key ?? '', b).pipe(
              map((view) => ({ view, error: null as string | null })),
              catchError((e: unknown) => of({ view: null, error: errorMessage(e) })),
            )
          : of(null),
      ),
    ),
    { initialValue: null },
  );

  protected insert(name: string): void {
    const el = this.area().nativeElement;
    const text = `{${name}}`;
    const start = el.selectionStart ?? this.body().length;
    const end = el.selectionEnd ?? start;
    this.body.set(this.body().slice(0, start) + text + this.body().slice(end));
    queueMicrotask(() => {
      el.focus();
      el.setSelectionRange(start + text.length, start + text.length);
    });
  }

  protected save(): void {
    this.run(this.settings.saveTemplate(this.data.template.key ?? '', this.body().trim()));
  }

  protected reset(): void {
    this.run(this.settings.resetTemplate(this.data.template.key ?? ''));
  }

  private run(request: ReturnType<SettingsService['saveTemplate']>): void {
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: (saved) => this.ref.close(saved),
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
