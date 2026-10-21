import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { IconShieldLock, TablerIconComponent } from '@tabler/icons-angular';

import { errorMessage } from '../../core/api/requests';
import { Invitation, Permission, Role, Staff, StaffService } from './data/staff.service';
import { PERMISSION_GROUPS, permissionLabel } from './permission-labels';

const STYLES = `
  form { display: flex; flex-direction: column; gap: 0.25rem; min-width: min(28rem, 80vw); padding-top: 0.5rem; }
  .error { color: var(--mat-sys-error); margin: 0; }
  .hint { color: var(--mat-sys-on-surface-variant); margin: 0 0 0.75rem; }
  .twofa { display: inline-flex; align-items: center; gap: 0.25rem; color: var(--app-warning); font: var(--mat-sys-label-medium); }
`;

/**
 * Invite a staff member: the temporary password (valid 72 hours) is shown once, and sent by SMS too
 * when asked (Q11, M3). The role decides whether 2FA is set up at the first sign-in (A2).
 */
@Component({
  selector: 'app-invite-staff-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    TablerIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Invite staff</h2>
    <mat-dialog-content>
      <p class="hint">
        They sign in with a temporary password, valid for 72 hours, and choose their own.
      </p>
      <form [formGroup]="form" id="invite-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput formControlName="displayName" maxlength="100" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Email</mat-label>
          <input matInput type="email" formControlName="email" autocomplete="off" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Phone</mat-label>
          <input matInput formControlName="phone" inputmode="tel" placeholder="0712 345 678" />
          <mat-hint>Optional; needed to send the password by SMS</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Role</mat-label>
          <mat-select formControlName="roleId">
            @for (role of data.roles; track role.id) {
              <mat-option [value]="role.id">{{ role.name }}</mat-option>
            }
          </mat-select>
          @if (selectedRole()?.requires2fa) {
            <mat-hint>
              <span class="twofa"
                ><tabler-icon [icon]="lockIcon" [size]="14" /> Sets up 2FA at first sign-in</span
              >
            </mat-hint>
          }
        </mat-form-field>
        <mat-checkbox formControlName="sendSms">
          Also send the temporary password by SMS{{ hasPhone() ? '' : ' (add a phone first)' }}
        </mat-checkbox>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="invite-form" [disabled]="form.invalid || busy()">
        Invite
      </button>
    </mat-dialog-actions>
  `,
})
export class InviteStaffDialogComponent {
  protected readonly data = inject<{ roles: Role[] }>(MAT_DIALOG_DATA);
  private readonly staff = inject(StaffService);
  private readonly ref = inject<MatDialogRef<InviteStaffDialogComponent, Invitation>>(MatDialogRef);
  protected readonly lockIcon = IconShieldLock;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    displayName: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email]],
    phone: [''],
    roleId: ['', Validators.required],
    sendSms: [false],
  });
  private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.value });
  protected readonly selectedRole = computed(() =>
    this.data.roles.find((r) => r.id === this.value().roleId),
  );
  protected readonly hasPhone = computed(() => !!this.value().phone?.trim());

  protected save(): void {
    const { displayName, email, phone, roleId, sendSms } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.staff
      .invite(
        {
          displayName: displayName.trim(),
          email: email.trim(),
          phone: phone.trim() || undefined,
          roleId,
        },
        sendSms && !!phone.trim(),
      )
      .subscribe({
        next: (invitation) => this.ref.close(invitation),
        error: (e: unknown) => {
          this.error.set(errorMessage(e));
          this.busy.set(false);
        },
      });
  }
}

/** Change a staff member's role; their sessions end and they sign in again with the new rights. */
@Component({
  selector: 'app-change-role-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatSelectModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Change role of {{ data.member.displayName }}</h2>
    <mat-dialog-content>
      <p class="hint">They are signed out everywhere and sign in again with the new role.</p>
      <form [formGroup]="form" id="role-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Role</mat-label>
          <mat-select formControlName="roleId">
            @for (role of data.roles; track role.id) {
              <mat-option [value]="role.id">{{ role.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button
        mat-flat-button
        type="submit"
        form="role-form"
        [disabled]="busy() || form.controls.roleId.value === data.member.roleId"
      >
        Change role
      </button>
    </mat-dialog-actions>
  `,
})
export class ChangeRoleDialogComponent {
  protected readonly data = inject<{ member: Staff; roles: Role[] }>(MAT_DIALOG_DATA);
  private readonly staff = inject(StaffService);
  private readonly ref = inject<MatDialogRef<ChangeRoleDialogComponent, Staff>>(MatDialogRef);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    roleId: [this.data.member.roleId ?? '', Validators.required],
  });

  protected save(): void {
    this.busy.set(true);
    this.error.set(null);
    this.staff.changeRole(this.data.member.id ?? '', this.form.getRawValue().roleId).subscribe({
      next: (member) => this.ref.close(member),
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}

/**
 * Create or edit a role (A2): permissions are fixed codes, grouped here by area. Holding any code the
 * API marks requires2fa makes the role require 2FA. The Owner role is shown read-only.
 */
@Component({
  selector: 'app-role-editor-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    TablerIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    ${STYLES}
    .group {
      margin: 0.75rem 0 0.25rem;
      font: var(--mat-sys-title-small);
      font-weight: 600;
    }
    .perm {
      display: flex;
      flex-direction: column;
      padding: 0.125rem 0;
    }
    .perm-text {
      display: flex;
      flex-direction: column;
    }
    .perm-label {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      font-weight: 500;
    }
    .perm-desc {
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-small);
    }
    .lock {
      color: var(--app-warning);
    }
    .summary {
      margin: 0.75rem 0 0;
    }
  `,
  template: `
    <h2 mat-dialog-title>
      {{ readOnly ? data.role?.name : data.role ? 'Edit ' + data.role.name : 'New role' }}
    </h2>
    <mat-dialog-content>
      @if (readOnly) {
        <p class="hint">The Owner role always has every permission and cannot be edited.</p>
      } @else if (data.role?.members; as members) {
        <p class="hint">
          {{ members }} {{ members === 1 ? 'person holds' : 'people hold' }}
          this role; they get the new rights at their next request.
        </p>
      }
      <form [formGroup]="form" id="role-editor" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput formControlName="name" maxlength="60" />
          @if (data.role?.builtIn && !readOnly) {
            <mat-hint>Built-in roles keep their name</mat-hint>
          }
        </mat-form-field>
        @for (group of groups(); track group.name) {
          <h3 class="group">{{ group.name }}</h3>
          @for (p of group.permissions; track p.code) {
            <mat-checkbox
              class="perm"
              [checked]="selected().has(p.code ?? '')"
              [disabled]="readOnly || p.code === ownerOnly"
              (change)="toggle(p.code ?? '', $event.checked)"
            >
              <span class="perm-text">
                <span class="perm-label">
                  {{ label(p.code).label }}
                  @if (p.requires2fa) {
                    <tabler-icon
                      class="lock"
                      [icon]="lockIcon"
                      [size]="14"
                      aria-label="requires 2FA"
                    />
                  }
                </span>
                <span class="perm-desc">{{ label(p.code).description }}</span>
              </span>
            </mat-checkbox>
          }
        }
        <p class="summary hint">
          @if (needs2fa()) {
            <span class="twofa"
              ><tabler-icon [icon]="lockIcon" [size]="14" /> This role requires 2FA (A2).</span
            >
          } @else {
            Any staff member can see customers, lines, routers and alerts; these add to that.
          }
        </p>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (readOnly) {
        <button mat-flat-button type="button" mat-dialog-close>Close</button>
      } @else {
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button
          mat-flat-button
          type="submit"
          form="role-editor"
          [disabled]="form.invalid || busy() || selected().size === 0"
        >
          {{ data.role ? 'Save' : 'Create role' }}
        </button>
      }
    </mat-dialog-actions>
  `,
})
export class RoleEditorDialogComponent {
  protected readonly data = inject<{ role: Role | null; permissions: Permission[] }>(
    MAT_DIALOG_DATA,
  );
  private readonly staff = inject(StaffService);
  private readonly ref = inject<MatDialogRef<RoleEditorDialogComponent, Role>>(MatDialogRef);
  protected readonly lockIcon = IconShieldLock;
  /** Held by the built-in Owner role only; the API refuses it for any other role. */
  protected readonly ownerOnly = 'settings.write';
  protected readonly label = (code: string | undefined) => permissionLabel(code ?? '');
  protected readonly readOnly = !!this.data.role?.builtIn && this.data.role.name === 'Owner';
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly selected = signal(new Set(this.data.role?.permissions ?? []));
  protected readonly form = inject(NonNullableFormBuilder).group({
    name: [
      { value: this.data.role?.name ?? '', disabled: !!this.data.role?.builtIn },
      [Validators.required, Validators.maxLength(60)],
    ],
  });

  protected readonly groups = computed(() =>
    PERMISSION_GROUPS.map((name) => ({
      name,
      permissions: this.data.permissions.filter(
        (p) => permissionLabel(p.code ?? '').group === name,
      ),
    })).filter((g) => g.permissions.length > 0),
  );

  protected readonly needs2fa = computed(() =>
    this.data.permissions.some((p) => p.requires2fa && this.selected().has(p.code ?? '')),
  );

  protected toggle(code: string, checked: boolean): void {
    this.selected.update((set) => {
      const next = new Set(set);
      if (checked) {
        next.add(code);
      } else {
        next.delete(code);
      }
      return next;
    });
  }

  protected save(): void {
    const name = this.form.getRawValue().name.trim();
    // Keep the catalogue's order, so a saved role reads the same as the editor.
    const permissions = this.data.permissions
      .map((p) => p.code ?? '')
      .filter((code) => this.selected().has(code));
    this.busy.set(true);
    this.error.set(null);
    const request = this.data.role
      ? this.staff.updateRole(this.data.role.id ?? '', name, permissions)
      : this.staff.createRole(name, permissions);
    request.subscribe({
      next: (role) => this.ref.close(role),
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
