import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import {
  IconArrowRight,
  IconChevronLeft,
  IconChevronRight,
  IconCircleCheck,
  IconRefresh,
  TablerIconComponent,
} from '@tabler/icons-angular';
import { EChartsCoreOption } from 'echarts/core';
import { catchError, map, of, switchMap, timer } from 'rxjs';

import { PermissionService } from '../../core/permissions/permission.service';
import { ChartComponent } from '../../shared/chart/chart.component';
import { formatBytes, formatDateTime, formatMoney } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import {
  StatusChipComponent,
  statusLabel,
  statusTone,
} from '../../shared/ui/status-chip.component';
import { AlertsService } from '../alerts/data/alerts.service';
import { PaymentsService } from '../payments/data/payments.service';
import { attentionItems } from './attention';
import { DashboardService, DayCollected } from './data/dashboard.service';

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
 * The admin dashboard (docs/09 step 8; docs/05 Dashboard figures; decisions.md 2026-10-20). It answers,
 * in order: what needs doing (the action queue, each row leading to the fix), how things stand right
 * now (lines, routers, sessions), and, for finance.read, the period's money with its daily trend and
 * the latest payments. Period money and current status sit apart and are labelled so. Figures are
 * text first; each chart has a text equivalent.
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
  private readonly alerts = inject(AlertsService);
  private readonly payments = inject(PaymentsService);
  private readonly permissions = inject(PermissionService);

  protected readonly icons = {
    prev: IconChevronLeft,
    next: IconChevronRight,
    refresh: IconRefresh,
    go: IconArrowRight,
    clear: IconCircleCheck,
  };
  protected readonly canSeeMoney = computed(() =>
    this.permissions.permissions().has('finance.read'),
  );
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

  /** Open alerts feed the queue; a failure leaves the queue to the dashboard's own figures. */
  private readonly openAlerts = rxResource({
    stream: () =>
      timer(0, REFRESH_MS).pipe(
        switchMap(() =>
          this.alerts.list('OPEN', { page: 0, size: 50, filters: {} }).pipe(
            map((page) => page.items),
            catchError(() => of([])),
          ),
        ),
      ),
  });

  protected readonly recentPayments = rxResource({
    params: () => this.canSeeMoney() || undefined,
    stream: () =>
      timer(0, REFRESH_MS).pipe(
        switchMap(() =>
          this.payments
            .list({ page: 0, size: 6, sort: 'receivedAt,desc', filters: {} })
            .pipe(map((page) => page.items)),
        ),
      ),
  });

  protected readonly queue = computed(() =>
    attentionItems(this.data.value(), this.openAlerts.value()),
  );

  protected readonly collection = computed(() => this.data.value()?.revenue?.collectedByDay ?? []);
  protected readonly collectionChart = computed(() => collectionChart(this.collection()));
  protected readonly collectionSummary = computed(() => collectionSummary(this.collection()));

  protected readonly displayRows = computed(() => {
    const counts = this.data.value()?.lines?.byDisplayStatus ?? {};
    return DISPLAY_ORDER.map((status) => ({ status, count: counts[status] ?? 0 }));
  });

  protected readonly subtitle = computed(() => {
    const d = this.data.value();
    return d ? `Updated ${formatDateTime(d.asOf)} · ${d.zone}` : null;
  });
  /** Lines that can use the internet now: active, expiring or in grace (display status, S1). */
  protected readonly linesWithService = computed(() => {
    const counts = this.data.value()?.lines?.byDisplayStatus ?? {};
    return (counts['ACTIVE'] ?? 0) + (counts['EXPIRING'] ?? 0) + (counts['GRACE'] ?? 0);
  });
  protected readonly restrictedLines = computed(
    () => this.data.value()?.lines?.byDisplayStatus?.['RESTRICTED'] ?? 0,
  );
  protected readonly when = formatDateTime;
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

/** Daily collections as thin bars in the tenant colour, one series, so no legend; a tooltip per day. */
export function collectionChart(days: DayCollected[]): EChartsCoreOption {
  return {
    textStyle: CHART_TEXT,
    grid: { left: 4, right: 8, top: 12, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { opacity: 0.25 } },
      formatter: (params: { dataIndex: number }[]) => {
        const day = days[params[0]?.dataIndex ?? 0];
        if (!day) {
          return '';
        }
        const count = day.payments ?? 0;
        return `${dayLabel(day.day)}<br/><b>${formatMoney(day.amount)}</b> · ${count} ${
          count === 1 ? 'payment' : 'payments'
        }`;
      },
    },
    xAxis: {
      type: 'category',
      data: days.map((d) => dayLabel(d.day)),
      axisLabel: { color: MUTED, fontSize: 11, hideOverlap: true },
      axisLine: { lineStyle: { color: 'var(--mat-sys-outline-variant)' } },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: MUTED, fontSize: 11, formatter: shortShillings },
      splitLine: { lineStyle: { color: 'var(--mat-sys-outline-variant)', type: 'dashed' } },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 12,
        // Shillings set the axis scale only; the tooltip shows the exact amount from minor units.
        data: days.map((d) => (d.amount?.amount ?? 0) / 100),
        itemStyle: { color: 'var(--mat-sys-primary)', borderRadius: [4, 4, 0, 0] },
      },
    ],
  };
}

/** The chart's text equivalent: the total, the number of days and the best day. */
export function collectionSummary(days: DayCollected[]): string {
  if (days.length === 0) {
    return 'No days in this period yet.';
  }
  const best = days.reduce((a, b) => ((b.amount?.amount ?? 0) > (a.amount?.amount ?? 0) ? b : a));
  const total = days.reduce((sum, d) => sum + (d.amount?.amount ?? 0), 0);
  const parts = [
    `Collected ${formatMoney({ amount: total, currency: 'KES' })} over ${days.length} ${
      days.length === 1 ? 'day' : 'days'
    }`,
  ];
  if ((best.amount?.amount ?? 0) > 0) {
    parts.push(`highest on ${dayLabel(best.day)} with ${formatMoney(best.amount)}`);
  }
  return parts.join(', ') + '.';
}

function dayLabel(iso: string | undefined): string {
  if (!iso) {
    return '';
  }
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
}

/** `12k`, `1.2M` or `850` shillings for axis ticks. */
function shortShillings(value: number): string {
  if (value >= 1_000_000) {
    return `${Math.round(value / 100_000) / 10}M`;
  }
  if (value >= 1_000) {
    return `${Math.round(value / 100) / 10}k`;
  }
  return String(value);
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
