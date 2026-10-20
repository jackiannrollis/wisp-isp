import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { IconPlug } from '@tabler/icons-angular';

import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import {
  ColumnDef,
  FilterDef,
  RowAction,
  TableFetch,
} from '../../shared/data-table/data-table.types';
import { formatDateTime } from '../../shared/ui/format';
import { StatusChipComponent, statusLabel } from '../../shared/ui/status-chip.component';
import { Customer, CustomersService } from './data/customers.service';

/** Customers: search by number, name or phone; filter by status; open a customer's lines. */
@Component({
  selector: 'app-customers-page',
  imports: [DataTableComponent, CellDirective, StatusChipComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <h1>Customers</h1>
      <app-data-table
        tableId="customers"
        exportName="customers"
        searchPlaceholder="Search number, name or phone"
        emptyText="No customers match."
        [columns]="columns"
        [filters]="filters"
        [fetch]="fetch"
        [actions]="actions"
        [defaultSort]="{ key: 'number', direction: 'asc' }"
      >
        <ng-template appCell="status" let-row>
          <app-status-chip [status]="row.status" />
        </ng-template>
      </app-data-table>
    </section>
  `,
})
export class CustomersPageComponent {
  private readonly customers = inject(CustomersService);
  private readonly router = inject(Router);

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
