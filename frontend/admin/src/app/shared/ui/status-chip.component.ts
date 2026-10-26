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
 * Status as coloured text with an icon, no container (ADR 0002 amendment, user choice): never colour
 * alone, since the label and the icon shape carry the meaning too. The colours are the fixed status
 * tokens of styles.scss, the same for every tenant.
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
  INVITED: 'warning',
  RESTRICTED: 'error',
  CAPPED: 'error',
  OFFLINE: 'error',
  SUSPENDED: 'error',
  REJECT: 'error',
  REVERSED: 'error',
  PENDING: 'neutral',
  PAUSED: 'neutral',
  TERMINATED: 'neutral',
  ARCHIVED: 'neutral',
  NOT_MONITORED: 'neutral',
  DISABLED: 'neutral',
  RETIRED: 'neutral',
};

export function statusTone(status: string | null | undefined): StatusTone {
  return (status && TONES[status]) || 'neutral';
}

/** Why access is limited, as the API's accessReason says it (null when it is not). */
const REASONS: Record<string, string> = {
  EXPIRED: 'expired',
  PAUSED: 'paused',
  GRACE_THROTTLE: 'grace period',
  FAIR_USE: 'fair use',
  DATA_CAP: 'data cap used up',
};

export function accessReasonLabel(reason: string | null | undefined): string {
  return reason ? (REASONS[reason] ?? reason.toLowerCase().replace(/_/g, ' ')) : '';
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
  template: `<span class="status" [class]="tone()"
    ><tabler-icon [icon]="icon()" [size]="15" aria-hidden="true" />{{ label() }}</span
  >`,
  styles: `
    .status {
      display: inline-flex;
      align-items: center;
      gap: 0.3125rem;
      font: var(--mat-sys-label-large);
      font-weight: 600;
      white-space: nowrap;
    }
    .success {
      color: var(--app-success);
    }
    .warning {
      color: var(--app-warning);
    }
    .error {
      color: var(--app-error);
    }
    .neutral {
      color: var(--app-neutral);
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
