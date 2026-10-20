import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  IconAlertTriangle,
  IconDotsVertical,
  IconKey,
  IconPlugConnectedX,
  IconRefresh,
  IconShieldLock,
  TablerIconComponent,
} from '@tabler/icons-angular';
import { Observable, timer } from 'rxjs';

import { errorMessage } from '../../core/api/requests';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  ReasonDialogComponent,
  ReasonDialogData,
} from '../../shared/dialogs/reason-dialog.component';
import {
  SecretDialogComponent,
  SecretDialogData,
} from '../../shared/dialogs/secret-dialog.component';
import { formatAge, formatDateTime, formatDuration } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { StatusChipComponent } from '../../shared/ui/status-chip.component';
import { Router, RouterWithSecret, RoutersService } from './data/routers.service';
import { RestCredentialDialogComponent } from './router-dialogs';

/** The worker polls on request; its answer lands within a few seconds (ADR 0011). */
const POLL_SETTLE_MS = 4000;

/**
 * One router: what the last poll reported, and its sessions two ways (docs/06 Accounting): radacct's
 * open sessions beside the router's own /ppp/active list as the worker last read it, with its age.
 * The API never talks to the router; "Poll now" asks the tunnel worker and re-reads shortly after.
 */
@Component({
  selector: 'app-router-detail-page',
  imports: [
    PageHeaderComponent,
    StatusChipComponent,
    MatButtonModule,
    MatMenuModule,
    MatTooltipModule,
    MatSnackBarModule,
    TablerIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './router-detail-page.component.html',
  styleUrl: './router-detail-page.component.scss',
})
export class RouterDetailPageComponent {
  readonly id = input.required<string>();

  private readonly routers = inject(RoutersService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  private readonly permissions = inject(PermissionService);

  protected readonly icons = {
    refresh: IconRefresh,
    more: IconDotsVertical,
    login: IconKey,
    stop: IconPlugConnectedX,
    secret: IconShieldLock,
    alert: IconAlertTriangle,
  };
  protected readonly date = formatDateTime;
  protected readonly age = formatAge;
  protected readonly duration = formatDuration;
  protected readonly canWrite = computed(() => this.permissions.permissions().has('network.write'));
  protected readonly polling = signal(false);

  protected readonly router = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.routers.get(params),
  });
  protected readonly sessions = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.routers.sessions(params),
  });

  /** Usernames the router reports, to mark each RADIUS session found there or not. */
  protected readonly onRouter = computed(
    () => new Set(this.sessions.value()?.router?.usernames ?? []),
  );

  protected pollNow(): void {
    this.polling.set(true);
    this.routers.pollNow(this.id()).subscribe({
      next: () =>
        timer(POLL_SETTLE_MS).subscribe(() => {
          this.reload();
          this.polling.set(false);
        }),
      error: (e: unknown) => {
        this.polling.set(false);
        this.snack.open(errorMessage(e), 'Dismiss', { duration: 6000 });
      },
    });
  }

  protected setLogin(router: Router): void {
    this.dialog
      .open(RestCredentialDialogComponent, { data: { router } })
      .afterClosed()
      .subscribe((saved) => saved && this.reload());
  }

  protected stopMonitoring(router: Router): void {
    this.confirm({
      title: `Stop monitoring ${router.name}?`,
      message:
        'The stored REST login is deleted and the router is no longer polled. RADIUS is not affected.',
      confirm: 'Stop monitoring',
      destructive: true,
      run: () => this.routers.removeRestCredential(router.id ?? ''),
    }).subscribe((done) => done && this.reload());
  }

  protected newSecret(router: Router): void {
    this.confirm<RouterWithSecret>({
      title: `New RADIUS secret for ${router.name}?`,
      message:
        'Logins through this router fail until the new secret is set on it (/radius in RouterOS). Do this during a maintenance window.',
      confirm: 'Generate',
      destructive: true,
      run: () => this.routers.newRadiusSecret(router.id ?? ''),
    }).subscribe((created) => {
      if (created) {
        this.dialog.open<SecretDialogComponent, SecretDialogData>(SecretDialogComponent, {
          disableClose: true,
          data: {
            title: `New RADIUS secret for ${router.name}`,
            message: 'Set it on the router for both RADIUS servers. It is shown only now.',
            rows: [{ label: 'RADIUS secret', value: created.radiusSecret ?? '', secret: true }],
          },
        });
      }
    });
  }

  private reload(): void {
    this.router.reload();
    this.sessions.reload();
  }

  private confirm<T>(data: ReasonDialogData<T>): Observable<T | undefined> {
    return this.dialog
      .open<ReasonDialogComponent<T>, ReasonDialogData<T>, T>(ReasonDialogComponent, { data })
      .afterClosed();
  }
}
