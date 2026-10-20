import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import {
  IconAlertTriangle,
  IconCircleCheck,
  IconCircleDashed,
  IconCircleX,
  TablerIconComponent,
} from '@tabler/icons-angular';

export type StatusTone = 'success' | 'warning' | 'error' | 'neutral';

/**
 * Status always shows text and an icon, never colour alone (ADR 0002 amendment). The colours are the
 * fixed status tokens of styles.scss, the same for every tenant.
 */
const TONES: Record<string, StatusTone> = {
  ACTIVE: 'success',
  ONLINE: 'success',
  FULL: 'success',
  MATCHED: 'success',
  EXPIRING: 'warning',
  GRACE: 'warning',
  THROTTLED: 'warning',
  UNMATCHED: 'warning',
  UNKNOWN: 'warning',
  RESTRICTED: 'error',
  OFFLINE: 'error',
  SUSPENDED: 'error',
  REJECT: 'error',
  REVERSED: 'error',
  PENDING: 'neutral',
  PAUSED: 'neutral',
  TERMINATED: 'neutral',
  ARCHIVED: 'neutral',
  NOT_MONITORED: 'neutral',
};

export function statusTone(status: string | null | undefined): StatusTone {
  return (status && TONES[status]) || 'neutral';
}

/** ACTIVE → Active, NOT_MONITORED → Not monitored. */
export function statusLabel(status: string | null | undefined): string {
  if (!status) {
    return '—';
  }
  const words = status.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

@Component({
  selector: 'app-status-chip',
  imports: [TablerIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="chip" [class]="tone()">
      <tabler-icon [icon]="icon()" [size]="16" aria-hidden="true" />
      {{ label() }}
    </span>
  `,
  styles: `
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.125rem 0.625rem 0.125rem 0.375rem;
      border-radius: var(--mat-sys-corner-full);
      font: var(--mat-sys-label-medium);
      white-space: nowrap;
    }
    .success {
      background: var(--app-success-container);
      color: var(--app-on-success-container);
    }
    .warning {
      background: var(--app-warning-container);
      color: var(--app-on-warning-container);
    }
    .error {
      background: var(--app-error-container);
      color: var(--app-on-error-container);
    }
    .neutral {
      background: transparent;
      color: var(--mat-sys-on-surface-variant);
      box-shadow: inset 0 0 0 1px var(--mat-sys-outline);
    }
  `,
})
export class StatusChipComponent {
  readonly status = input<string | null | undefined>(null);

  protected readonly tone = computed(() => statusTone(this.status()));
  protected readonly label = computed(() => statusLabel(this.status()));
  protected readonly icon = computed(() => {
    switch (this.tone()) {
      case 'success':
        return IconCircleCheck;
      case 'warning':
        return IconAlertTriangle;
      case 'error':
        return IconCircleX;
      default:
        return IconCircleDashed;
    }
  });
}
