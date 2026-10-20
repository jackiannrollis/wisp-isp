import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { TablerIconComponent } from '@tabler/icons-angular';

import { errorMessage } from '../../core/api/requests';
import { PermissionService } from '../../core/permissions/permission.service';
import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import { ColumnDef, TableFetch } from '../../shared/data-table/data-table.types';
import { formatAge, formatDateTime } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { alertKind, alertLink } from './alert-kinds';
import { Alert, AlertState, AlertsService, PhoneFlag } from './data/alerts.service';

const FLAG_STATUS: Record<string, string> = {
  INVALID_NUMBER: 'Invalid number',
  BLACKLISTED: 'Blocked by the customer',
};

/**
 * Staff alerts (docs/08 Monitoring): routers offline, CoA or Disconnect that failed after its retries
 * (N5) and SMS account problems. An alert clears by itself when the problem goes away; the cleared
 * view is the history. Below, the numbers the SMS provider refused for good, which staff clear after
 * correcting the customer's number.
 */
@Component({
  selector: 'app-alerts-page',
  imports: [
    DataTableComponent,
    CellDirective,
    PageHeaderComponent,
    MatButtonModule,
    MatButtonToggleModule,
    MatSnackBarModule,
    TablerIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alerts-page.component.html',
  styleUrl: './alerts-page.component.scss',
})
export class AlertsPageComponent {
  private readonly alerts = inject(AlertsService);
  private readonly snack = inject(MatSnackBar);
  private readonly permissions = inject(PermissionService);

  protected readonly kind = alertKind;
  protected readonly date = formatDateTime;
  protected readonly state = signal<AlertState>('OPEN');
  protected readonly open = computed(() => this.state() === 'OPEN');
  protected readonly canClearFlags = computed(() =>
    this.permissions.permissions().has('settings.notifications'),
  );

  protected readonly fetch = computed<TableFetch<Alert>>(() => {
    const state = this.state();
    return (query) => this.alerts.list(state, query);
  });

  protected readonly link = alertLink;

  protected readonly columns = computed<ColumnDef<Alert>[]>(() => {
    const open = this.open();
    const columns: ColumnDef<Alert>[] = [
      { key: 'kind', header: 'Alert', value: (a) => alertKind(a.kind).label },
      { key: 'subject', header: 'About', value: (a) => a.subjectLabel ?? '' },
      { key: 'action', header: 'What to do', value: (a) => alertKind(a.kind).action },
      {
        key: 'raisedAt',
        header: 'Raised',
        value: (a) => (open ? formatAge(a.raisedAt) : formatDateTime(a.raisedAt)),
      },
    ];
    if (!open) {
      columns.push({
        key: 'clearedAt',
        header: 'Cleared',
        value: (a) => formatDateTime(a.clearedAt),
      });
    }
    columns.push({
      key: 'detail',
      header: 'Detail',
      value: (a) => a.detail,
      hiddenByDefault: true,
    });
    return columns;
  });

  protected readonly flagsVersion = signal(0);
  protected readonly flags = rxResource({
    params: () => this.flagsVersion(),
    stream: () => this.alerts.phoneFlags(),
  });

  protected flagStatus(flag: PhoneFlag): string {
    return FLAG_STATUS[flag.status ?? ''] ?? flag.status ?? '';
  }

  protected clearFlag(flag: PhoneFlag): void {
    const phone = flag.phone ?? '';
    this.alerts.clearPhoneFlag(phone).subscribe({
      next: () => {
        this.snack.open(`Messages to ${phone} will be sent again.`, undefined, { duration: 4000 });
        this.flagsVersion.update((v) => v + 1);
      },
      error: (e: unknown) => this.snack.open(errorMessage(e), 'Dismiss', { duration: 8000 }),
    });
  }
}
