import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Router as Navigation } from '@angular/router';
import { IconPlus, IconRefresh, TablerIconComponent } from '@tabler/icons-angular';

import { PermissionService } from '../../core/permissions/permission.service';
import { errorMessage } from '../../core/api/requests';
import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import { ColumnDef, RowAction, TableFetch } from '../../shared/data-table/data-table.types';
import {
  SecretDialogComponent,
  SecretDialogData,
} from '../../shared/dialogs/secret-dialog.component';
import { formatAge, formatDateTime, formatDuration } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { StatusChipComponent, statusLabel } from '../../shared/ui/status-chip.component';
import { Router, RouterWithSecret, RoutersService } from './data/routers.service';
import { RegisterRouterDialogComponent } from './router-dialogs';

/**
 * Routers: monitoring status from the tunnel worker's polls (ADR 0011), last seen and RouterOS facts.
 * Status says whether the REST API answered; RADIUS accounting is only the secondary "last heard".
 */
@Component({
  selector: 'app-routers-page',
  imports: [
    DataTableComponent,
    CellDirective,
    StatusChipComponent,
    PageHeaderComponent,
    MatButtonModule,
    MatSnackBarModule,
    TablerIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .key {
      font-weight: 600;
    }
    .error-text {
      color: var(--app-error);
    }
  `,
  template: `
    <app-page-header
      title="Routers"
      subtitle="Status comes from the monitoring poll every 60 seconds over the WireGuard tunnel."
    >
      @if (canWrite()) {
        <button actions mat-flat-button type="button" (click)="register()">
          <tabler-icon [icon]="icons.add" [size]="18" /> Register router
        </button>
      }
    </app-page-header>
    <app-data-table
      tableId="routers"
      exportName="routers"
      emptyText="No routers registered yet."
      [itemLabel]="['router', 'routers']"
      [columns]="columns"
      [fetch]="fetch"
      [actions]="actions()"
      [rowLink]="link"
      [refresh]="version()"
      [defaultSort]="{ key: 'name', direction: 'asc' }"
    >
      <ng-template appCell="name" let-row
        ><span class="key">{{ row.name }}</span></ng-template
      >
      <ng-template appCell="status" let-row><app-status-chip [status]="row.status" /></ng-template>
      <ng-template appCell="lastPollError" let-row>
        <span [class.error-text]="!!row.lastPollError">{{ row.lastPollError || '—' }}</span>
      </ng-template>
    </app-data-table>
  `,
})
export class RoutersPageComponent {
  private readonly routers = inject(RoutersService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  private readonly navigation = inject(Navigation);
  private readonly permissions = inject(PermissionService);

  protected readonly icons = { add: IconPlus };
  protected readonly canWrite = computed(() => this.permissions.permissions().has('network.write'));
  protected readonly version = signal(0);
  protected readonly link = (r: Router) => `/network/${r.id}`;
  protected readonly fetch: TableFetch<Router> = (query) => this.routers.list(query);

  protected readonly columns: ColumnDef<Router>[] = [
    { key: 'name', header: 'Name', value: (r) => r.name, sortKey: 'name' },
    { key: 'tunnelIp', header: 'Tunnel address', value: (r) => r.tunnelIp },
    { key: 'status', header: 'Status', value: (r) => statusLabel(r.status) },
    { key: 'lastSeenAt', header: 'Last seen', value: (r) => formatAge(r.lastSeenAt) },
    { key: 'version', header: 'RouterOS', value: (r) => r.routerosVersion },
    {
      key: 'uptime',
      header: 'Uptime',
      value: (r) => formatDuration(r.uptimeSeconds),
      align: 'end',
    },
    {
      key: 'cpu',
      header: 'CPU',
      value: (r) => (r.cpuLoad === undefined || r.cpuLoad === null ? '—' : `${r.cpuLoad}%`),
      align: 'end',
      hiddenByDefault: true,
    },
    { key: 'board', header: 'Board', value: (r) => r.boardName, hiddenByDefault: true },
    {
      key: 'lastAccountingAt',
      header: 'Last accounting',
      value: (r) => formatAge(r.lastAccountingAt),
      hiddenByDefault: true,
    },
    {
      key: 'lastPollError',
      header: 'Last poll error',
      value: (r) => r.lastPollError,
      hiddenByDefault: true,
    },
    {
      key: 'createdAt',
      header: 'Registered',
      value: (r) => formatDateTime(r.createdAt),
      sortKey: 'createdAt',
      hiddenByDefault: true,
    },
  ];

  protected readonly actions = computed<RowAction<Router>[]>(() =>
    this.canWrite()
      ? [
          {
            label: 'Poll now',
            icon: IconRefresh,
            visible: (r) => !!r.restUsername,
            run: (r) => this.pollNow(r),
          },
        ]
      : [],
  );

  protected register(): void {
    this.dialog
      .open(RegisterRouterDialogComponent)
      .afterClosed()
      .subscribe((created: RouterWithSecret | undefined) => {
        if (!created) {
          return;
        }
        this.version.update((v) => v + 1);
        this.dialog
          .open<SecretDialogComponent, SecretDialogData>(SecretDialogComponent, {
            disableClose: true,
            data: {
              title: `RADIUS secret for ${created.router?.name}`,
              message: 'The onboarding script puts this on the router. It is shown only now.',
              rows: [{ label: 'RADIUS secret', value: created.radiusSecret ?? '', secret: true }],
            },
          })
          .afterClosed()
          .subscribe(() => void this.navigation.navigate(['/network', created.router?.id]));
      });
  }

  private pollNow(router: Router): void {
    this.routers.pollNow(router.id ?? '').subscribe({
      next: () => {
        this.snack.open(`Asked the monitoring worker to poll ${router.name}.`, undefined, {
          duration: 3000,
        });
        setTimeout(() => this.version.update((v) => v + 1), 4000);
      },
      error: (e: unknown) => this.snack.open(errorMessage(e), 'Dismiss', { duration: 6000 }),
    });
  }
}
