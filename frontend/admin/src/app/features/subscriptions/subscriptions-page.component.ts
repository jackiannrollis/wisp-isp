import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';

import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import { ColumnDef, FilterDef, TableFetch } from '../../shared/data-table/data-table.types';
import { formatDateTime } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { StatusChipComponent, statusLabel } from '../../shared/ui/status-chip.component';
import { RoutersService } from '../network/data/routers.service';
import { PackagesService } from '../packages/data/packages.service';
import { Line, LinesService } from './data/lines.service';
import { accessText, usageText } from './line-format';

const DISPLAY_STATUSES = [
  'PENDING',
  'ACTIVE',
  'EXPIRING',
  'GRACE',
  'RESTRICTED',
  'THROTTLED',
  'CAPPED',
  'PAUSED',
  'SUSPENDED',
  'TERMINATED',
];

/**
 * Lines (PPPoE subscriptions). The display status is derived by the server (S1) and filtered there,
 * so "Restricted" lists every line that is restricted right now, for collections. A customer's lines
 * arrive with `customerId` (and a label) in the URL, shown as a removable chip.
 */
@Component({
  selector: 'app-subscriptions-page',
  imports: [DataTableComponent, CellDirective, StatusChipComponent, PageHeaderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .key {
      font-weight: 600;
    }
  `,
  template: `
    <app-page-header
      title="Lines"
      subtitle="PPPoE lines with the status and access the network gives them right now."
    />
    <app-data-table
      tableId="lines"
      exportName="lines"
      emptyText="No lines match."
      [itemLabel]="['line', 'lines']"
      [columns]="columns()"
      [filters]="filters()"
      [presetFilters]="presets()"
      [initialFilters]="initial()"
      [fetch]="fetch"
      [rowLink]="link"
      [defaultSort]="{ key: 'pppoeUsername', direction: 'asc' }"
    >
      <ng-template appCell="displayStatus" let-row>
        <app-status-chip [status]="row.displayStatus" />
      </ng-template>
      <ng-template appCell="state" let-row>
        <app-status-chip [status]="row.state" />
      </ng-template>
      <ng-template appCell="username" let-row
        ><span class="key">{{ row.pppoeUsername }}</span></ng-template
      >
    </app-data-table>
  `,
})
export class SubscriptionsPageComponent {
  private readonly lines = inject(LinesService);

  /** From `?customerId=...&customer=...` (Customers → View lines). */
  readonly customerId = input<string>();
  readonly customer = input<string>();
  /** From `?displayStatus=...` (the dashboard's status list). */
  readonly displayStatus = input<string>();

  private readonly packages = toSignal(
    inject(PackagesService)
      .all()
      .pipe(catchError(() => of([]))),
    {
      initialValue: [],
    },
  );
  private readonly routers = toSignal(
    inject(RoutersService)
      .all()
      .pipe(catchError(() => of([]))),
    {
      initialValue: [],
    },
  );
  private readonly packageNames = computed(
    () => new Map(this.packages().map((p) => [p.id, p.name])),
  );
  private readonly routerNames = computed(() => new Map(this.routers().map((r) => [r.id, r.name])));

  protected readonly fetch: TableFetch<Line> = (query) => this.lines.list(query);
  /** A line opens its customer, where its actions are. */
  protected readonly link = (l: Line) => `/customers/${l.customerId}`;

  protected readonly presets = computed(() => {
    const presets: Record<string, { value: string; label: string }> = {};
    const id = this.customerId();
    if (id) {
      presets['customerId'] = { value: id, label: `Customer ${this.customer() ?? id}` };
    }
    return presets;
  });

  protected readonly initial = computed<Record<string, string>>(() => {
    const status = this.displayStatus();
    const initial: Record<string, string> = {};
    if (status && DISPLAY_STATUSES.includes(status)) {
      initial['displayStatus'] = status;
    }
    return initial;
  });

  protected readonly columns = computed<ColumnDef<Line>[]>(() => {
    const packages = this.packageNames();
    const routers = this.routerNames();
    return [
      {
        key: 'username',
        header: 'Username',
        value: (l) => l.pppoeUsername,
        sortKey: 'pppoeUsername',
      },
      {
        key: 'package',
        header: 'Package',
        value: (l) => (l.packageId && packages.get(l.packageId)) || '—',
      },
      { key: 'displayStatus', header: 'Status', value: (l) => statusLabel(l.displayStatus) },
      { key: 'accessProfile', header: 'Access', value: (l) => accessText(l) },
      { key: 'usage', header: 'Cycle usage', value: (l) => usageText(l), align: 'end' },
      {
        key: 'expiresAt',
        header: 'Expires',
        value: (l) => formatDateTime(l.expiresAt),
        sortKey: 'expiresAt',
      },
      {
        key: 'router',
        header: 'Router',
        value: (l) => (l.routerId && routers.get(l.routerId)) || '—',
        hiddenByDefault: true,
      },
      {
        key: 'state',
        header: 'Stored state',
        value: (l) => statusLabel(l.state),
        sortKey: 'state',
        hiddenByDefault: true,
      },
      {
        key: 'graceDays',
        header: 'Grace days',
        value: (l) => l.graceDays,
        align: 'end',
        hiddenByDefault: true,
      },
      {
        key: 'createdAt',
        header: 'Opened',
        value: (l) => formatDateTime(l.createdAt),
        sortKey: 'createdAt',
        hiddenByDefault: true,
      },
    ];
  });

  protected readonly filters = computed<FilterDef[]>(() => [
    {
      key: 'displayStatus',
      label: 'Status',
      options: DISPLAY_STATUSES.map((s) => ({ value: s, label: statusLabel(s) })),
    },
    ...(this.packages().length > 1
      ? [
          {
            key: 'packageId',
            label: 'Package',
            options: this.packages().map((p) => ({ value: p.id ?? '', label: p.name ?? '' })),
          },
        ]
      : []),
    ...(this.routers().length > 1
      ? [
          {
            key: 'routerId',
            label: 'Router',
            options: this.routers().map((r) => ({ value: r.id ?? '', label: r.name ?? '' })),
          },
        ]
      : []),
  ]);
}
