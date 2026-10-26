import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { RouterLink } from '@angular/router';
import { Observable, map } from 'rxjs';

import { errorMessage } from '../../core/api/requests';
import { formatDateTime, formatDateTimeOrClosed } from '../../shared/format';
import { Impersonation, Invitation, Staff, Tenant } from '../../shared/models';
import { StatusChipComponent } from '../../shared/status-chip.component';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
  CutoverWindowDialogComponent,
  PasswordDialogComponent,
  PasswordDialogData,
} from './tenant-dialogs';
import { Gate1PanelComponent } from './gate1-panel.component';
import { TenantsService } from './tenants.service';

/**
 * One tenant as Corebalt sees it (docs/07 Platform layer): its status (activate, suspend with a reason,
 * reinstate), the cutover window (ADR 0014), Gate 1 as measured (ADR 0018), its staff with an unused
 * invitation reissued, and the impersonations of its staff. Everything here is recorded in the tenant's audit log and the platform's.
 */
@Component({
  selector: 'app-tenant-page',
  imports: [
    Gate1PanelComponent,
    MatButtonModule,
    MatSnackBarModule,
    RouterLink,
    StatusChipComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './tenant-page.component.html',
  styleUrl: './tenants.scss',
})
export class TenantPageComponent {
  readonly id = input.required<string>();

  private readonly service = inject(TenantsService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);

  protected readonly date = formatDateTime;
  protected readonly window = formatDateTimeOrClosed;

  protected readonly tenant = rxResource({
    params: () => this.id(),
    stream: ({ params }) =>
      this.service.list().pipe(map((all) => all.find((t) => t.id === params) ?? null)),
  });
  protected readonly staff = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.service.staff(params),
  });
  private readonly allImpersonations = rxResource({ stream: () => this.service.impersonations() });
  protected readonly impersonations = computed(() =>
    (this.allImpersonations.value() ?? []).filter((i) => i.tenantId === this.id()),
  );
  protected readonly windowOpen = computed(() => {
    const until = this.tenant.value()?.cutoverWindowUntil;
    return !!until && new Date(until).getTime() > Date.now();
  });

  protected staffName(staffId: string | undefined): string {
    const member = (this.staff.value() ?? []).find((s) => s.id === staffId);
    return member ? `${member.displayName} (${member.email})` : (staffId ?? '—');
  }

  protected isLive(i: Impersonation): boolean {
    return !i.endedAt && !!i.expiresAt && new Date(i.expiresAt).getTime() > Date.now();
  }

  protected activate(t: Tenant): void {
    this.confirm({
      title: `Activate ${t.name}?`,
      message:
        'From trial to active. Today the status changes nothing for the ISP; it is the bookkeeping the platform bills by (Q1).',
      confirm: 'Activate',
      run: () => this.service.activate(t.id ?? ''),
    });
  }

  protected suspend(t: Tenant): void {
    this.confirm({
      title: `Suspend ${t.name}?`,
      message:
        'Its staff and its customers can no longer sign in, and its scheduled jobs stop. M-Pesa callbacks, events and RADIUS logins continue: its customers stay online. Reinstate to undo.',
      confirm: 'Suspend',
      reasonLabel: 'Reason (kept in both audit logs)',
      destructive: true,
      run: (reason) => this.service.suspend(t.id ?? '', reason),
    });
  }

  protected reinstate(t: Tenant): void {
    this.confirm({
      title: `Reinstate ${t.name}?`,
      message: 'Sign-ins and scheduled jobs resume; the tenant becomes active.',
      confirm: 'Reinstate',
      run: () => this.service.reinstate(t.id ?? ''),
    });
  }

  protected openWindow(t: Tenant): void {
    this.dialog
      .open(CutoverWindowDialogComponent, { data: { tenant: t }, width: '480px' })
      .afterClosed()
      .subscribe((updated: Tenant | undefined) => updated && this.tenant.reload());
  }

  protected closeWindow(t: Tenant): void {
    this.confirm({
      title: `Close the cutover window of ${t.name}?`,
      message:
        'Imports with paid_until are refused from now on. Lines already imported stay as they are.',
      confirm: 'Close window',
      run: () => this.service.closeCutoverWindow(t.id ?? ''),
    });
  }

  protected reissue(t: Tenant, member: Staff): void {
    this.confirm<Invitation>({
      title: `New invitation for ${member.displayName}?`,
      message:
        'A new temporary password, valid 72 hours, replaces the one they never used. The old one stops working at once.',
      confirm: 'Reissue',
      run: () => this.service.reissueInvitation(t.id ?? '', member.id ?? ''),
    }).subscribe((invitation) => {
      if (invitation) {
        this.staff.reload();
        this.dialog.open<PasswordDialogComponent, PasswordDialogData>(PasswordDialogComponent, {
          disableClose: true,
          data: {
            title: `${member.displayName}: new temporary password`,
            message: `Give it by a channel other than email, with the code ${t.code}. It is shown only now.`,
            email: member.email,
            password: invitation.temporaryPassword ?? '',
            expiresAt: formatDateTime(invitation.expiresAt),
          },
        });
      }
    });
  }

  protected end(i: Impersonation): void {
    this.service.endImpersonation(i.id ?? '').subscribe({
      next: () => {
        this.allImpersonations.reload();
        this.snack.open(
          'Impersonation ended; its token stops working at the next request.',
          undefined,
          {
            duration: 4000,
          },
        );
      },
      error: (e: unknown) => this.snack.open(errorMessage(e), 'Dismiss', { duration: 6000 }),
    });
  }

  private confirm<T>(data: ConfirmDialogData<T>): Observable<T | undefined> {
    const closed = this.dialog
      .open<ConfirmDialogComponent<T>, ConfirmDialogData<T>, T>(ConfirmDialogComponent, {
        data,
        width: '480px',
      })
      .afterClosed();
    closed.subscribe((done) => done && this.tenant.reload());
    return closed;
  }
}
