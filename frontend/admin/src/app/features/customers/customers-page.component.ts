import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { Router, RouterLink } from '@angular/router';
import { IconFileUpload, IconPlug, IconPlus, TablerIconComponent } from '@tabler/icons-angular';

import { PermissionService } from '../../core/permissions/permission.service';

import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import {
  ColumnDef,
  FilterDef,
  RowAction,
  TableFetch,
} from '../../shared/data-table/data-table.types';
import { formatDateTime } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { StatusChipComponent, statusLabel } from '../../shared/ui/status-chip.component';
import { Customer, CustomersService } from './data/customers.service';
import { CustomerFormDialogComponent } from './dialogs/customer-dialogs';

/** Customers: search by number, name or phone; filter by status; open a customer's lines. */
@Component({
  selector: 'app-customers-page',
  imports: [
    DataTableComponent,
    CellDirective,
    StatusChipComponent,
    MatButtonModule,
    TablerIconComponent,
    PageHeaderComponent,
    RouterLink,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header
      title="Customers"
      subtitle="Everyone you sell a line to, with their contact details."
    >
      <ng-container actions>
        @if (canImport()) {
          <a mat-stroked-button routerLink="/customers/import">
            <tabler-icon [icon]="importIcon" [size]="18" /> Import
          </a>
        }
        @if (canCreate()) {
          <button mat-flat-button type="button" (click)="create()">
            <tabler-icon [icon]="addIcon" [size]="18" /> New customer
          </button>
        }
      </ng-container>
    </app-page-header>
    <app-data-table
      tableId="customers"
      exportName="customers"
      searchPlaceholder="Search number, name or phone"
      emptyText="No customers match."
      [itemLabel]="['customer', 'customers']"
      [columns]="columns"
      [filters]="filters"
      [fetch]="fetch"
      [actions]="actions"
      [rowLink]="link"
      [defaultSort]="{ key: 'number', direction: 'asc' }"
    >
      <ng-template appCell="number" let-row
        ><span class="key">{{ row.number }}</span></ng-template
      >
      <ng-template appCell="status" let-row>
        <app-status-chip [status]="row.status" />
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .key {
      font-weight: 600;
    }
  `,
})
export class CustomersPageComponent {
  private readonly customers = inject(CustomersService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly permissions = inject(PermissionService);

  protected readonly addIcon = IconPlus;
  protected readonly importIcon = IconFileUpload;
  protected readonly canCreate = computed(() =>
    this.permissions.permissions().has('customers.write'),
  );
  protected readonly canImport = computed(() => {
    const p = this.permissions.permissions();
    // The import opens lines with PPPoE passwords, a secret (ADR 0017: not while impersonating).
    return (
      p.has('customers.write') &&
      p.has('subscriptions.write') &&
      this.permissions.mayHandleSecrets()
    );
  });
  protected readonly link = (c: Customer) => `/customers/${c.id}`;

  protected create(): void {
    this.dialog
      .open(CustomerFormDialogComponent, { data: {} })
      .afterClosed()
      .subscribe((created: Customer | undefined) => {
        if (created) {
          void this.router.navigate(['/customers', created.id]);
        }
      });
  }

  protected readonly fetch: TableFetch<Customer> = (query) => this.customers.list(query);

  protected readonly columns: ColumnDef<Customer>[] = [
    { key: 'number', header: 'Number', value: (c) => c.number, sortKey: 'number' },
    { key: 'name', header: 'Name', value: (c) => c.name, sortKey: 'name' },
    { key: 'phone', header: 'Phone', value: (c) => c.phone },
    { key: 'email', header: 'Email', value: (c) => c.email, hiddenByDefault: true },
    {
      key: 'location',
      header: 'Installation',
      value: (c) => c.installationLocation,
      hiddenByDefault: true,
    },
    { key: 'status', header: 'Status', value: (c) => statusLabel(c.status) },
    {
      key: 'createdAt',
      header: 'Created',
      value: (c) => formatDateTime(c.createdAt),
      sortKey: 'createdAt',
    },
  ];

  protected readonly filters: FilterDef[] = [
    {
      key: 'status',
      label: 'Status',
      options: [
        { value: 'ACTIVE', label: 'Active' },
        { value: 'ARCHIVED', label: 'Archived' },
      ],
    },
  ];

  protected readonly actions: RowAction<Customer>[] = [
    {
      label: 'View lines',
      icon: IconPlug,
      run: (c) =>
        void this.router.navigate(['/subscriptions'], {
          queryParams: { customerId: c.id, customer: `${c.number} ${c.name}` },
        }),
    },
  ];
}
