import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';

import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import { ColumnDef, FilterDef, TableFetch } from '../../shared/data-table/data-table.types';
import { formatAge, formatDateTime, formatDuration } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { RoutersService } from './data/routers.service';
import { OpenSession, SessionsService } from './data/sessions.service';

/** Last heard longer ago than this is worth a look: the router sends an update every 5 minutes. */
const QUIET_SECONDS = 10 * 60;

/**
 * Who is online now (docs/06 Accounting), across the tenant's routers: each open RADIUS session with
 * its router, address and age, and whether the router's own list agreed at its last poll (ADR 0011).
 * Sessions silent for 15 minutes are closed by the stale job. Usage lives on the customer's page,
 * from the daily roll-up (N7).
 */
@Component({
  selector: 'app-sessions-page',
  imports: [DataTableComponent, CellDirective, PageHeaderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .user {
      font-weight: 600;
    }
    .quiet {
      color: var(--app-warning);
      font-weight: 600;
    }
    .agree {
      color: var(--app-success);
      font-weight: 600;
    }
    .disagree {
      color: var(--app-warning);
      font-weight: 600;
    }
    .unknown {
      color: var(--app-neutral);
    }
  `,
  template: `
    <app-page-header
      title="Sessions"
      subtitle="Who is online now, on which router and for how long. Updates arrive from each router every few minutes."
    />
    <app-data-table
      tableId="sessions"
      exportName="sessions"
      emptyText="No one is online."
      searchPlaceholder="Username starts with"
      [itemLabel]="['session', 'sessions']"
      [columns]="columns"
      [filters]="filters()"
      [fetch]="fetch"
      [rowLink]="link"
      [defaultSort]="{ key: 'username', direction: 'asc' }"
    >
      <ng-template appCell="username" let-row
        ><span class="user">{{ row.username }}</span></ng-template
      >
      <ng-template appCell="lastHeardAt" let-row>
        <span [class.quiet]="quiet(row)">{{ age(row.lastHeardAt) }}</span>
      </ng-template>
      <ng-template appCell="onRouter" let-row>
        @switch (row.onRouter) {
          @case (true) {
            <span class="agree">Yes</span>
          }
          @case (false) {
            <span class="disagree">Not in its list</span>
          }
          @default {
            <span class="unknown">Not polled</span>
          }
        }
      </ng-template>
    </app-data-table>
  `,
})
export class SessionsPageComponent {
  private readonly sessions = inject(SessionsService);

  private readonly routers = toSignal(
    inject(RoutersService)
      .all()
      .pipe(catchError(() => of([]))),
    { initialValue: [] },
  );

  protected readonly age = formatAge;
  protected readonly fetch: TableFetch<OpenSession> = (query) => this.sessions.list(query);
  /** A session opens its customer; one whose username matches no line opens its router. */
  protected readonly link = (s: OpenSession) =>
    s.customerId ? `/customers/${s.customerId}` : s.routerId ? `/network/${s.routerId}` : '';

  protected readonly filters = computed<FilterDef[]>(() =>
    this.routers().length > 1
      ? [
          {
            key: 'routerId',
            label: 'Router',
            options: this.routers().map((r) => ({ value: r.id ?? '', label: r.name ?? '' })),
          },
        ]
      : [],
  );

  protected readonly columns: ColumnDef<OpenSession>[] = [
    { key: 'username', header: 'Username', value: (s) => s.username, sortKey: 'username' },
    { key: 'router', header: 'Router', value: (s) => s.routerName ?? '—' },
    { key: 'address', header: 'Address', value: (s) => s.framedIp ?? '—' },
    {
      key: 'startedAt',
      header: 'Online for',
      value: (s) => onlineFor(s.startedAt),
      sortKey: 'startedAt',
    },
    {
      key: 'lastHeardAt',
      header: 'Last heard',
      value: (s) => formatAge(s.lastHeardAt),
      sortKey: 'lastHeardAt',
    },
    { key: 'onRouter', header: 'On router', value: (s) => onRouterText(s.onRouter) },
    {
      key: 'started',
      header: 'Started',
      value: (s) => formatDateTime(s.startedAt),
      hiddenByDefault: true,
    },
    {
      key: 'mac',
      header: 'Customer device',
      value: (s) => s.callingStationId ?? '—',
      hiddenByDefault: true,
    },
    {
      key: 'sessionId',
      header: 'Session ID',
      value: (s) => s.acctSessionId,
      hiddenByDefault: true,
    },
  ];

  protected quiet(s: OpenSession): boolean {
    return secondsSince(s.lastHeardAt) > QUIET_SECONDS;
  }
}

function secondsSince(iso: string | undefined, now = Date.now()): number {
  return iso ? Math.max(0, (now - new Date(iso).getTime()) / 1000) : 0;
}

/** `3d 4h` or `42m` since the session started. */
export function onlineFor(startedAt: string | undefined, now = Date.now()): string {
  return startedAt ? formatDuration(Math.round(secondsSince(startedAt, now))) : '—';
}

function onRouterText(value: boolean | undefined | null): string {
  return value === true ? 'Yes' : value === false ? 'Not in its list' : 'Not polled';
}
