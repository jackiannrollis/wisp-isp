import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { statusLabel } from './format';

const TONES: Record<string, 'success' | 'warning' | 'error' | 'neutral'> = {
  ACTIVE: 'success',
  TRIAL: 'warning',
  INVITED: 'warning',
  SUSPENDED: 'error',
  DISABLED: 'neutral',
  ENDED: 'neutral',
};

/** A status as a coloured label: colour and text together, never colour alone. */
@Component({
  selector: 'app-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip" [class]="tone()">{{ label() }}</span>`,
  styles: `
    .chip {
      display: inline-block;
      padding: 0.125rem 0.5rem;
      border-radius: 999px;
      font: var(--mat-sys-label-medium);
      white-space: nowrap;
    }
    .success {
      color: var(--app-success);
      background: var(--app-success-soft);
    }
    .warning {
      color: var(--app-warning);
      background: var(--app-warning-soft);
    }
    .error {
      color: var(--app-error);
      background: var(--app-error-soft);
    }
    .neutral {
      color: var(--app-neutral);
      background: var(--mat-sys-surface-container-high);
    }
  `,
})
export class StatusChipComponent {
  readonly status = input<string | null | undefined>();
  protected readonly label = computed(() => statusLabel(this.status()));
  protected readonly tone = computed(() => `chip ${TONES[this.status() ?? ''] ?? 'neutral'}`);
}
