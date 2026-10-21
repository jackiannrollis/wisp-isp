import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { UsageDay } from '../../shared/models';
import { formatBytes } from '../../shared/ui/format';

interface Bar {
  day: string;
  label: string;
  bytes: number;
  height: number;
}

const dayLabel = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' });

/**
 * Daily usage of one line (download plus upload) as thin bars, one per day, with the day under the
 * pointer or focus read out above them. A single series, so no legend; a plain list carries the same
 * numbers for screen readers.
 */
@Component({
  selector: 'app-usage-bars',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="head">
      <span>{{ readout() }}</span>
      <span class="muted">{{ total() }} in the last {{ bars().length }} days</span>
    </div>
    <div class="bars" aria-hidden="true" (mouseleave)="active.set(null)">
      @for (bar of bars(); track bar.day) {
        <div
          class="slot"
          [class.on]="active() === bar"
          (mouseenter)="active.set(bar)"
          [title]="bar.label + ': ' + bytes(bar.bytes)"
        >
          <div class="bar" [style.height.%]="bar.height"></div>
        </div>
      }
    </div>
    <div class="axis muted" aria-hidden="true">
      <span>{{ bars()[0]?.label }}</span>
      <span>{{ bars()[bars().length - 1]?.label }}</span>
    </div>
    <ul class="sr-only">
      @for (bar of bars(); track bar.day) {
        <li>{{ bar.label }}: {{ bytes(bar.bytes) }}</li>
      }
    </ul>
  `,
  styles: `
    :host {
      display: block;
      padding-bottom: 0.75rem;
    }
    .head {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      font: var(--mat-sys-label-large);
      margin-bottom: 0.5rem;
    }
    .bars {
      display: flex;
      align-items: flex-end;
      gap: 2px;
      height: 6rem;
      border-bottom: var(--app-border);
    }
    .slot {
      flex: 1;
      height: 100%;
      display: flex;
      align-items: flex-end;
    }
    .bar {
      width: 100%;
      min-height: 1px;
      border-radius: 4px 4px 0 0;
      background: var(--mat-sys-primary);
      opacity: 0.85;
    }
    .slot.on .bar {
      opacity: 1;
      outline: 2px solid var(--mat-sys-surface);
    }
    .axis {
      display: flex;
      justify-content: space-between;
      font: var(--mat-sys-label-small);
      margin-top: 0.25rem;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
      white-space: nowrap;
    }
  `,
})
export class UsageBarsComponent {
  readonly days = input.required<UsageDay[]>();

  protected readonly active = signal<Bar | null>(null);
  /** The API returns only days with traffic; the chart shows every day of the window, zero or not. */
  protected readonly bars = computed<Bar[]>(() => {
    const byDay = new Map(
      this.days().map((d) => [d.day ?? '', (d.inputOctets ?? 0) + (d.outputOctets ?? 0)]),
    );
    const window = lastDays(WINDOW, [...byDay.keys()].sort().at(-1));
    const peak = Math.max(1, ...byDay.values());
    return window.map((day) => {
      const bytes = byDay.get(day) ?? 0;
      return {
        day,
        label: dayLabel.format(new Date(`${day}T00:00:00`)),
        bytes,
        height: (bytes / peak) * 100,
      };
    });
  });
  protected readonly total = computed(() =>
    formatBytes(this.bars().reduce((sum, bar) => sum + bar.bytes, 0)),
  );
  protected readonly readout = computed(() => {
    const bar = this.active();
    return bar ? `${bar.label}: ${formatBytes(bar.bytes)}` : 'Daily usage';
  });

  protected readonly bytes = formatBytes;
}

/** The API's default window (PortalUsageController: the last 30 days). */
const WINDOW = 30;

/**
 * `count` ISO dates ending today, or on the latest day with data when that is later (the provider's
 * day may run ahead of the browser's).
 */
export function lastDays(count: number, latest?: string, today: Date = new Date()): string[] {
  const local = isoDate(today);
  const end = new Date(`${latest && latest > local ? latest : local}T00:00:00`);
  return Array.from({ length: count }, (_, i) => {
    const d = new Date(end);
    d.setDate(end.getDate() - (count - 1 - i));
    return isoDate(d);
  });
}

function isoDate(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
