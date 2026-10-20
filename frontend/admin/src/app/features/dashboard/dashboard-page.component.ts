import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import {
  IconChevronLeft,
  IconChevronRight,
  IconRefresh,
  TablerIconComponent,
} from '@tabler/icons-angular';
import { EChartsCoreOption } from 'echarts/core';
import { switchMap, timer } from 'rxjs';

import { ChartComponent } from '../../shared/chart/chart.component';
import { formatBytes, formatDateTime, formatMoney } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import {
  StatusChipComponent,
  statusLabel,
  statusTone,
} from '../../shared/ui/status-chip.component';
import { DashboardService } from './data/dashboard.service';

/** How often the dashboard re-reads its figures (conventions.md: polling until WebSockets in v2). */
export const REFRESH_MS = 30_000;

const DISPLAY_ORDER = [
  'ACTIVE',
  'EXPIRING',
  'GRACE',
  'RESTRICTED',
  'PENDING',
  'PAUSED',
  'SUSPENDED',
];
const ROUTER_ORDER = ['ONLINE', 'OFFLINE', 'UNKNOWN', 'NOT_MONITORED'];

const MUTED = 'var(--mat-sys-on-surface-variant)';
const CHART_TEXT = { fontFamily: "'Inter Variable', system-ui, sans-serif" };

const TONE_COLOUR = {
  success: 'var(--app-success)',
  warning: 'var(--app-warning)',
  error: 'var(--app-error)',
  neutral: 'var(--app-neutral)',
} as const;

/**
 * The admin dashboard (docs/09 step 8; docs/05 Dashboard figures). Figures are shown as text first;
 * each chart repeats a list beside it. Money appears only when the API sends it (finance.read).
 */
@Component({
  selector: 'app-dashboard-page',
  imports: [
    RouterLink,
    MatButtonModule,
    PageHeaderComponent,
    MatTooltipModule,
    TablerIconComponent,
    ChartComponent,
    StatusChipComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard-page.component.html',
  styleUrl: './dashboard-page.component.scss',
})
export class DashboardPageComponent {
  private readonly dashboards = inject(DashboardService);

  protected readonly icons = {
    prev: IconChevronLeft,
    next: IconChevronRight,
    refresh: IconRefresh,
  };
  protected readonly money = formatMoney;
  protected readonly bytes = formatBytes;
  protected readonly label = statusLabel;

  /** The first day of the shown month (browser-local calendar; the server applies the tenant zone). */
  protected readonly month = signal(firstOfMonth(new Date()));
  protected readonly isCurrentMonth = computed(
    () => this.month().getTime() === firstOfMonth(new Date()).getTime(),
  );
  protected readonly monthLabel = computed(() =>
    this.month().toLocaleDateString('en-GB', { month: 'long', year: 'numeric' }),
  );

  protected readonly data = rxResource({
    params: () => this.month(),
    stream: ({ params }) =>
      timer(0, REFRESH_MS).pipe(
        switchMap(() => this.dashboards.get(isoDate(params), isoDate(lastOfMonth(params)))),
      ),
  });

  protected readonly displayRows = computed(() => {
    const counts = this.data.value()?.lines?.byDisplayStatus ?? {};
    return DISPLAY_ORDER.map((status) => ({ status, count: counts[status] ?? 0 }));
  });

  protected readonly subtitle = computed(() => {
    const d = this.data.value();
    return d ? `Updated ${formatDateTime(d.asOf)} · ${d.zone}` : null;
  });
  protected readonly activeLines = computed(
    () => this.data.value()?.lines?.byState?.['ACTIVE'] ?? 0,
  );
  /** Lines someone may need to act on: about to expire, in grace, or restricted. */
  protected readonly attention = computed(() => {
    const counts = this.data.value()?.lines?.byDisplayStatus ?? {};
    return (counts['EXPIRING'] ?? 0) + (counts['GRACE'] ?? 0) + (counts['RESTRICTED'] ?? 0);
  });
  protected readonly routersOnline = computed(
    () => this.data.value()?.network?.routersByStatus?.['ONLINE'] ?? 0,
  );
  protected readonly routersOffline = computed(
    () => this.data.value()?.network?.routersByStatus?.['OFFLINE'] ?? 0,
  );
  protected readonly routersTotal = computed(() =>
    Object.values(this.data.value()?.network?.routersByStatus ?? {}).reduce((a, b) => a + b, 0),
  );

  protected readonly routerRows = computed(() => {
    const counts = this.data.value()?.network?.routersByStatus ?? {};
    return ROUTER_ORDER.map((status) => ({ status, count: counts[status] ?? 0 }));
  });

  protected readonly linesChart = computed<EChartsCoreOption>(() => {
    const rows = this.displayRows();
    return {
      textStyle: CHART_TEXT,
      grid: { left: 4, right: 12, top: 4, bottom: 4, containLabel: true },
      tooltip: { trigger: 'item' },
      xAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { color: MUTED, fontSize: 11 },
        splitLine: { lineStyle: { color: 'var(--mat-sys-outline-variant)', type: 'dashed' } },
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: rows.map((r) => statusLabel(r.status)),
        axisLabel: { color: MUTED, fontSize: 12 },
        axisLine: { show: false },
        axisTick: { show: false },
      },
      series: [
        {
          type: 'bar',
          barMaxWidth: 14,
          data: rows.map((r) => ({
            value: r.count,
            itemStyle: { color: TONE_COLOUR[statusTone(r.status)], borderRadius: [0, 3, 3, 0] },
          })),
        },
      ],
    };
  });

  protected readonly routersChart = computed<EChartsCoreOption>(() => ({
    textStyle: CHART_TEXT,
    tooltip: { trigger: 'item' },
    series: [
      {
        type: 'pie',
        radius: ['64%', '84%'],
        padAngle: 2,
        itemStyle: { borderRadius: 3 },
        label: { show: false },
        data: this.routerRows()
          .filter((r) => r.count > 0)
          .map((r) => ({
            name: statusLabel(r.status),
            value: r.count,
            itemStyle: { color: TONE_COLOUR[statusTone(r.status)] },
          })),
      },
    ],
  }));

  protected readonly linesSummary = computed(() =>
    this.displayRows()
      .map((r) => `${statusLabel(r.status)} ${r.count}`)
      .join(', '),
  );
  protected readonly routersSummary = computed(() =>
    this.routerRows()
      .map((r) => `${statusLabel(r.status)} ${r.count}`)
      .join(', '),
  );

  protected step(months: number): void {
    const current = this.month();
    this.month.set(new Date(current.getFullYear(), current.getMonth() + months, 1));
  }
}

function firstOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}

function lastOfMonth(first: Date): Date {
  return new Date(first.getFullYear(), first.getMonth() + 1, 0);
}

function isoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}
