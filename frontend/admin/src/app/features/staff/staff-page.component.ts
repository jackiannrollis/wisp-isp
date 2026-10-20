import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import {
  IconKey,
  IconMessage,
  IconPlus,
  IconShieldLock,
  IconUserCheck,
  IconUserCog,
  IconUserOff,
  IconUserPlus,
  TablerIconComponent,
} from '@tabler/icons-angular';
import { Observable } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import {
  ColumnDef,
  FilterDef,
  RowAction,
  TableFetch,
} from '../../shared/data-table/data-table.types';
import {
  ReasonDialogComponent,
  ReasonDialogData,
} from '../../shared/dialogs/reason-dialog.component';
import {
  SecretDialogComponent,
  SecretDialogData,
} from '../../shared/dialogs/secret-dialog.component';
import { formatAge, formatDateTime } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { StatusChipComponent, statusLabel } from '../../shared/ui/status-chip.component';
import { Invitation, Role, Staff, StaffService } from './data/staff.service';
import { permissionLabel } from './permission-labels';
import {
  ChangeRoleDialogComponent,
  InviteStaffDialogComponent,
  RoleEditorDialogComponent,
} from './staff-dialogs';

const SMS_DELIVERY: Record<string, string> = {
  SENT: 'It was also sent by SMS to their phone.',
  NOT_CONFIGURED: 'SMS is not set up for this account, so it was not sent by SMS.',
  FAILED: 'The SMS could not be sent; give it to them another way.',
};

/**
 * Staff and roles (staff.manage, docs/08): invite, reset, disable and re-role staff; create and edit
 * roles from the fixed permission codes (A2). Every change here is audited by the API (A1), and a
 * disable, a reset or a role change ends that person's sessions at their next request.
 */
@Component({
  selector: 'app-staff-page',
  imports: [
    DataTableComponent,
    CellDirective,
    PageHeaderComponent,
    StatusChipComponent,
    MatButtonModule,
    MatTabsModule,
    MatSnackBarModule,
    TablerIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './staff-page.component.html',
  styleUrl: './staff-page.component.scss',
})
export class StaffPageComponent {
  private readonly staff = inject(StaffService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  private readonly me = inject(AuthService).me;

  protected readonly icons = { invite: IconUserPlus, add: IconPlus, lock: IconShieldLock };
  protected readonly permissionLabel = (code: string) => permissionLabel(code).label;
  protected readonly version = signal(0);

  protected readonly roles = rxResource({
    params: () => this.version(),
    stream: () => this.staff.roles(),
  });
  protected readonly permissions = rxResource({ stream: () => this.staff.permissions() });
  private readonly roleById = computed(
    () => new Map((this.roles.value() ?? []).map((r) => [r.id ?? '', r])),
  );

  protected readonly fetch: TableFetch<Staff> = (query) => this.staff.list(query);

  protected readonly columns: ColumnDef<Staff>[] = [
    {
      key: 'displayName',
      header: 'Name',
      value: (s) => `${s.displayName ?? ''} · ${s.email ?? ''}`,
      sortKey: 'displayName',
    },
    { key: 'role', header: 'Role', value: (s) => s.roleName },
    { key: 'status', header: 'Status', value: (s) => statusLabel(s.status) },
    { key: 'twoFa', header: '2FA', value: (s) => this.twoFa(s).text },
    {
      key: 'lastLoginAt',
      header: 'Last sign-in',
      value: (s) => formatAge(s.lastLoginAt),
      sortKey: 'lastLoginAt',
    },
    { key: 'phone', header: 'Phone', value: (s) => s.phone, hiddenByDefault: true },
    {
      key: 'invitationExpiresAt',
      header: 'Invitation expires',
      value: (s) => formatDateTime(s.invitationExpiresAt),
      hiddenByDefault: true,
    },
    {
      key: 'createdAt',
      header: 'Added',
      value: (s) => formatDateTime(s.createdAt),
      sortKey: 'createdAt',
      hiddenByDefault: true,
    },
  ];

  protected readonly filters: FilterDef[] = [
    {
      key: 'status',
      label: 'Status',
      options: [
        { value: 'ACTIVE', label: 'Active' },
        { value: 'INVITED', label: 'Invited' },
        { value: 'DISABLED', label: 'Disabled' },
      ],
    },
  ];

  /** The API refuses changing your own role or disabling yourself; the menu does not offer it. */
  private readonly notMe = (s: Staff) => s.id !== this.me()?.id;

  protected readonly actions: RowAction<Staff>[] = [
    {
      label: 'Change role',
      icon: IconUserCog,
      run: (s) => this.changeRole(s),
      visible: (s) => this.notMe(s) && s.status !== 'DISABLED',
    },
    {
      label: 'New temporary password',
      icon: IconKey,
      run: (s) => this.newPassword(s, false),
      visible: (s) => this.notMe(s) && s.status !== 'DISABLED',
    },
    {
      label: 'New temporary password by SMS',
      icon: IconMessage,
      run: (s) => this.newPassword(s, true),
      visible: (s) => this.notMe(s) && s.status !== 'DISABLED' && !!s.phone,
    },
    {
      label: 'Disable',
      icon: IconUserOff,
      run: (s) => this.disable(s),
      visible: (s) => this.notMe(s) && s.status !== 'DISABLED',
    },
    {
      label: 'Enable',
      icon: IconUserCheck,
      run: (s) => this.enable(s),
      visible: (s) => s.status === 'DISABLED',
    },
  ];

  /** 2FA as the person stands: on, due at their next sign-in (their role needs it, A2), or off. */
  protected twoFa(s: Staff): { text: string; tone: 'ok' | 'due' | 'off' } {
    if (s.totpEnabled) {
      return { text: 'On', tone: 'ok' };
    }
    return this.roleById().get(s.roleId ?? '')?.requires2fa
      ? { text: 'Set up at next sign-in', tone: 'due' }
      : { text: 'Off', tone: 'off' };
  }

  protected invite(): void {
    this.dialog
      .open(InviteStaffDialogComponent, { data: { roles: this.roles.value() ?? [] } })
      .afterClosed()
      .subscribe((invitation: Invitation | undefined) => {
        if (invitation) {
          this.refresh();
          this.showPassword(invitation, 'Invitation created');
        }
      });
  }

  protected editRole(role: Role | null): void {
    this.dialog
      .open(RoleEditorDialogComponent, {
        data: { role, permissions: this.permissions.value() ?? [] },
        autoFocus: role?.builtIn ? 'dialog' : 'first-tabbable',
      })
      .afterClosed()
      .subscribe((saved: Role | undefined) => {
        if (saved) {
          this.snack.open(`Role ${saved.name} saved.`, undefined, { duration: 4000 });
          this.refresh();
        }
      });
  }

  private changeRole(member: Staff): void {
    this.dialog
      .open(ChangeRoleDialogComponent, { data: { member, roles: this.roles.value() ?? [] } })
      .afterClosed()
      .subscribe((changed: Staff | undefined) => {
        if (changed) {
          this.snack.open(`${changed.displayName} is now ${changed.roleName}.`, undefined, {
            duration: 4000,
          });
          this.refresh();
        }
      });
  }

  private newPassword(member: Staff, sendSms: boolean): void {
    const invited = member.status === 'INVITED';
    this.confirm<Invitation>({
      title: invited ? `New invitation for ${member.displayName}` : `Reset ${member.displayName}`,
      message: invited
        ? 'The old temporary password stops working. The new one is valid for 72 hours.'
        : 'Their password stops working and they are signed out everywhere. They choose a new one with the temporary password.',
      confirm: sendSms ? 'Create and send by SMS' : 'Create password',
      run: () => this.staff.temporaryPassword(member.id ?? '', sendSms),
    }).subscribe((invitation) => {
      if (invitation) {
        this.refresh();
        this.showPassword(invitation, 'New temporary password');
      }
    });
  }

  private disable(member: Staff): void {
    this.confirm<Staff>({
      title: `Disable ${member.displayName}`,
      message: 'They are signed out at their next request and cannot sign in until enabled again.',
      confirm: 'Disable',
      reasonLabel: 'Reason (kept in the audit log)',
      destructive: true,
      run: (reason) => this.staff.disable(member.id ?? '', reason),
    }).subscribe((changed) => changed && this.refresh());
  }

  private enable(member: Staff): void {
    this.staff.enable(member.id ?? '').subscribe(() => {
      this.snack.open(`${member.displayName} can sign in again.`, undefined, { duration: 4000 });
      this.refresh();
    });
  }

  private confirm<T>(data: ReasonDialogData<T>): Observable<T | undefined> {
    return this.dialog
      .open<ReasonDialogComponent<T>, ReasonDialogData<T>, T>(ReasonDialogComponent, { data })
      .afterClosed();
  }

  private showPassword(invitation: Invitation, title: string): void {
    const delivery = SMS_DELIVERY[invitation.smsDelivery ?? ''] ?? '';
    const data: SecretDialogData = {
      title,
      message:
        `Give ${invitation.staff?.displayName} this temporary password; it is valid until ` +
        `${formatDateTime(invitation.expiresAt)}. ${delivery}`.trim(),
      rows: [
        { label: 'Email', value: invitation.staff?.email ?? '' },
        { label: 'Temporary password', value: invitation.temporaryPassword ?? '', secret: true },
      ],
    };
    this.dialog.open(SecretDialogComponent, { data, disableClose: true });
  }

  private refresh(): void {
    this.version.update((v) => v + 1);
  }
}
