import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { Router, RouterLink } from '@angular/router';
import { IconPlus, TablerIconComponent } from '@tabler/icons-angular';

import { formatDateTime, formatDateTimeOrClosed } from '../../shared/format';
import { CreatedTenant } from '../../shared/models';
import { StatusChipComponent } from '../../shared/status-chip.component';
import {
  CreateTenantDialogComponent,
  PasswordDialogComponent,
  PasswordDialogData,
} from './tenant-dialogs';
import { TenantsService } from './tenants.service';

/**
 * Every tenant on the platform (docs/07 Platform layer): its code, portal, status and cutover window.
 * A new tenant starts in TRIAL with its Owner invited; the Owner's temporary password is shown once.
 */
@Component({
  selector: 'app-tenants-page',
  imports: [MatButtonModule, TablerIconComponent, RouterLink, StatusChipComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="head">
      <div>
        <h1>Tenants</h1>
        <p class="muted">The ISPs on the platform. Open one to change its status or help it.</p>
      </div>
      <button mat-flat-button type="button" (click)="create()">
        <tabler-icon [icon]="addIcon" [size]="18" /> New tenant
      </button>
    </header>

    @if (tenants.error()) {
      <div class="panel empty">
        <p>The tenants could not be loaded.</p>
        <button mat-stroked-button type="button" (click)="tenants.reload()">Try again</button>
      </div>
    } @else {
      <div class="panel table-wrap">
        <table>
          <thead>
            <tr>
              <th>Code</th>
              <th>Name</th>
              <th>Portal</th>
              <th>Status</th>
              <th>Cutover window</th>
            </tr>
          </thead>
          <tbody>
            @for (t of tenants.value() ?? []; track t.id) {
              <tr>
                <td>
                  <a class="key" [routerLink]="['/tenants', t.id]">{{ t.code }}</a>
                </td>
                <td>{{ t.name }}</td>
                <td class="muted">{{ t.subdomain }}</td>
                <td><app-status-chip [status]="t.status" /></td>
                <td>{{ window(t.cutoverWindowUntil) }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="5" class="muted empty-row">
                  {{ tenants.isLoading() ? 'Loading…' : 'No tenants yet.' }}
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
  styleUrl: './tenants.scss',
})
export class TenantsPageComponent {
  private readonly service = inject(TenantsService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  protected readonly addIcon = IconPlus;
  protected readonly date = formatDateTime;
  protected readonly window = formatDateTimeOrClosed;
  protected readonly tenants = rxResource({ stream: () => this.service.list() });

  protected create(): void {
    this.dialog
      .open(CreateTenantDialogComponent, { width: '560px' })
      .afterClosed()
      .subscribe((created: CreatedTenant | undefined) => {
        if (!created?.tenant) {
          return;
        }
        this.tenants.reload();
        this.dialog
          .open<PasswordDialogComponent, PasswordDialogData>(PasswordDialogComponent, {
            disableClose: true,
            data: {
              title: `${created.tenant.name}: the Owner's temporary password`,
              message:
                'Give it to the Owner by a channel other than email, with the address of the admin app and the code ' +
                `${created.tenant.code}. It is shown only now; if it is lost, reissue the invitation from the tenant's page.`,
              email: created.owner?.staff?.email,
              password: created.owner?.temporaryPassword ?? '',
              expiresAt: formatDateTime(created.owner?.expiresAt),
            },
          })
          .afterClosed()
          .subscribe(() => void this.router.navigate(['/tenants', created.tenant?.id]));
      });
  }
}
