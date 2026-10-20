import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';

import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import {
  ColumnDef,
  FilterDef,
  TableFetch,
  TableQuery,
} from '../../shared/data-table/data-table.types';
import { formatDateTime } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { AuditDetailDialogComponent } from './audit-detail-dialog.component';
import { AUDIT_AREAS, actionLabel, actorLabel, diff, targetLabel } from './audit-labels';
import { AuditRow, AuditService } from './data/audit.service';

/**
 * The tenant's audit log (A1, audit.read), newest first: every money movement, access change,
 * secret change and impersonation, with the actor and before and after. Rows open their detail;
 * `?targetId=` or `?actorId=` narrows the log to one thing or one person.
 */
@Component({
  selector: 'app-audit-page',
  imports: [DataTableComponent, CellDirective, PageHeaderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .what {
      font-weight: 600;
    }
    .support {
      color: var(--app-warning);
      font-weight: 600;
    }
    .change {
      display: block;
      max-width: 22rem;
      overflow: hidden;
      text-overflow: ellipsis;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
  template: `
    <app-page-header
      title="Audit log"
      subtitle="Every change to money, access, secrets and staff, with who made it and why. Entries can never be edited or removed."
    />
    <app-data-table
      tableId="audit-log"
      exportName="audit-log"
      emptyText="No entries match."
      [itemLabel]="['entry', 'entries']"
      [columns]="columns"
      [filters]="filters"
      [presetFilters]="presets()"
      [fetch]="fetch"
      [rowSelect]="open"
      [defaultSort]="{ key: 'occurredAt', direction: 'desc' }"
    >
      <ng-template appCell="action" let-row
        ><span class="what">{{ action(row.action) }}</span></ng-template
      >
      <ng-template appCell="actor" let-row>
        <span [class.support]="row.actorType === 'PLATFORM'">{{ actorName(row) }}</span>
      </ng-template>
      <ng-template appCell="change" let-row
        ><span class="change">{{ summary(row) }}</span></ng-template
      >
    </app-data-table>
  `,
})
export class AuditPageComponent {
  private readonly audit = inject(AuditService);
  private readonly dialog = inject(MatDialog);

  /** From the detail dialog: everything about one target, or everything by one person. */
  readonly targetId = input<string>();
  readonly target = input<string>();
  readonly actorId = input<string>();
  readonly actor = input<string>();

  protected readonly action = actionLabel;
  protected readonly actorName = actorLabel;
  protected readonly fetch: TableFetch<AuditRow> = (query) => this.audit.list(withFrom(query));
  protected readonly open = (row: AuditRow) =>
    this.dialog.open(AuditDetailDialogComponent, { data: row, autoFocus: 'dialog' });

  protected readonly presets = computed(() => {
    const presets: Record<string, { value: string; label: string }> = {};
    const targetId = this.targetId();
    if (targetId) {
      presets['targetId'] = {
        value: targetId,
        label: `${this.target() ?? 'Item'} ${short(targetId)}`,
      };
    }
    const actorId = this.actorId();
    if (actorId) {
      presets['actorId'] = { value: actorId, label: `By ${this.actor() ?? short(actorId)}` };
    }
    return presets;
  });

  protected readonly columns: ColumnDef<AuditRow>[] = [
    {
      key: 'occurredAt',
      header: 'When',
      value: (r) => formatDateTime(r.occurredAt),
      sortKey: 'occurredAt',
    },
    { key: 'actor', header: 'Who', value: (r) => actorLabel(r) },
    { key: 'action', header: 'What', value: (r) => actionLabel(r.action) },
    { key: 'target', header: 'About', value: (r) => targetLabel(r.targetType) },
    { key: 'change', header: 'Change', value: (r) => this.summary(r) },
    { key: 'reason', header: 'Reason', value: (r) => r.reason },
    { key: 'code', header: 'Action code', value: (r) => r.action, hiddenByDefault: true },
  ];

  protected readonly filters: FilterDef[] = [
    {
      key: 'period',
      label: 'When',
      options: [
        { value: 'today', label: 'Today' },
        { value: 'week', label: 'Last 7 days' },
        { value: 'month', label: 'Last 30 days' },
      ],
    },
    { key: 'action', label: 'Area', options: AUDIT_AREAS },
  ];

  /** The changed fields in one line, `Status: ACTIVE → DISABLED`; the dialog has the full table. */
  protected summary(row: AuditRow): string {
    return diff(row.before, row.after)
      .filter((c) => c.changed && !c.id)
      .map((c) => (c.before ? `${c.field}: ${c.before} → ${c.after}` : `${c.field}: ${c.after}`))
      .join('; ');
  }
}

/** Replaces the `period` filter with `from`, at the start of today, or 7 or 30 days back. */
export function withFrom(query: TableQuery, now = new Date()): TableQuery {
  const { period, ...filters } = query.filters;
  if (!period) {
    return query;
  }
  const from = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  from.setDate(from.getDate() - (period === 'week' ? 6 : period === 'month' ? 29 : 0));
  return { ...query, filters: { ...filters, from: from.toISOString() } };
}

function short(id: string): string {
  return id.slice(-6);
}
