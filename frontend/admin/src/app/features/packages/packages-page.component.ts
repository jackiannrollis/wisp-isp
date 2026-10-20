import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import {
  IconEdit,
  IconPlayerPlay,
  IconPlayerStop,
  IconPlus,
  TablerIconComponent,
} from '@tabler/icons-angular';
import { Observable } from 'rxjs';

import { errorMessage } from '../../core/api/requests';
import { PermissionService } from '../../core/permissions/permission.service';
import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import {
  ColumnDef,
  FilterDef,
  RowAction,
  TableFetch,
} from '../../shared/data-table/data-table.types';
import { formatBytes, formatMoney } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { Package, PackagesService } from './data/packages.service';
import { PackageDialogComponent } from './package-dialog.component';
import { formatPeriod, formatSpeeds } from './package-format';

/**
 * The package catalogue (packages.write to change it). Stopping sales hides a package from new
 * lines while existing lines keep it; a revision applies from each line's next renewal (S6).
 */
@Component({
  selector: 'app-packages-page',
  imports: [
    DataTableComponent,
    CellDirective,
    PageHeaderComponent,
    MatButtonModule,
    MatSnackBarModule,
    TablerIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .name {
      font-weight: 600;
    }
    .price {
      font-weight: 600;
    }
    .on-sale {
      color: var(--app-success);
      font-weight: 600;
    }
    .off-sale {
      color: var(--app-neutral);
      font-weight: 600;
    }
  `,
  template: `
    <app-page-header
      title="Packages"
      subtitle="What you sell: speed, period and price. Changes reach each line at its next renewal."
    >
      @if (canWrite()) {
        <ng-container actions>
          <button mat-flat-button type="button" (click)="edit(null)">
            <tabler-icon [icon]="addIcon" [size]="16" />
            New package
          </button>
        </ng-container>
      }
    </app-page-header>
    <app-data-table
      tableId="packages"
      exportName="packages"
      emptyText="No packages yet."
      [itemLabel]="['package', 'packages']"
      [columns]="columns"
      [filters]="filters"
      [initialFilters]="{ active: 'true' }"
      [fetch]="fetch"
      [actions]="actions()"
      [rowSelect]="canWrite() ? select : null"
      [refresh]="version()"
      [defaultSort]="{ key: 'name', direction: 'asc' }"
    >
      <ng-template appCell="name" let-row
        ><span class="name">{{ row.name }}</span></ng-template
      >
      <ng-template appCell="price" let-row
        ><span class="price">{{ money(row.price) }}</span></ng-template
      >
      <ng-template appCell="active" let-row>
        <span [class]="row.active ? 'on-sale' : 'off-sale'">{{
          row.active ? 'On sale' : 'Not on sale'
        }}</span>
      </ng-template>
    </app-data-table>
  `,
})
export class PackagesPageComponent {
  private readonly packages = inject(PackagesService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  private readonly permissions = inject(PermissionService);

  protected readonly addIcon = IconPlus;
  protected readonly money = formatMoney;
  protected readonly version = signal(0);
  protected readonly canWrite = computed(() =>
    this.permissions.permissions().has('packages.write'),
  );

  protected readonly fetch: TableFetch<Package> = (query) => this.packages.list(query);
  protected readonly select = (pkg: Package) => this.edit(pkg);

  protected readonly columns: ColumnDef<Package>[] = [
    { key: 'name', header: 'Name', value: (p) => p.name, sortKey: 'name' },
    {
      key: 'speed',
      header: 'Download / upload',
      value: (p) => formatSpeeds(p.downloadKbps, p.uploadKbps),
    },
    { key: 'period', header: 'Period', value: (p) => formatPeriod(p.durationMinutes) },
    {
      key: 'price',
      header: 'Price',
      value: (p) => formatMoney(p.price),
      sortKey: 'priceMinor',
      align: 'end',
    },
    {
      key: 'grace',
      header: 'Grace',
      value: (p) =>
        p.graceDays === null || p.graceDays === undefined
          ? 'Account default'
          : `${p.graceDays} ${p.graceDays === 1 ? 'day' : 'days'}`,
    },
    { key: 'active', header: 'Status', value: (p) => (p.active ? 'On sale' : 'Not on sale') },
    {
      key: 'fup',
      header: 'Fair use',
      value: (p) => (p.fupBytes ? formatBytes(p.fupBytes) : '—'),
      hiddenByDefault: true,
    },
    {
      key: 'cap',
      header: 'Data cap',
      value: (p) => (p.dataCapBytes ? formatBytes(p.dataCapBytes) : '—'),
      hiddenByDefault: true,
    },
  ];

  protected readonly filters: FilterDef[] = [
    {
      key: 'active',
      label: 'Status',
      options: [
        { value: 'true', label: 'On sale' },
        { value: 'false', label: 'Not on sale' },
      ],
    },
  ];

  protected readonly actions = computed<RowAction<Package>[]>(() =>
    this.canWrite()
      ? [
          { label: 'Edit', icon: IconEdit, run: (p) => this.edit(p) },
          {
            label: 'Stop selling',
            icon: IconPlayerStop,
            run: (p) =>
              this.toggle(this.packages.deactivate(p.id ?? ''), `${p.name} is no longer sold.`),
            visible: (p) => !!p.active,
          },
          {
            label: 'Sell again',
            icon: IconPlayerPlay,
            run: (p) => this.toggle(this.packages.activate(p.id ?? ''), `${p.name} is on sale.`),
            visible: (p) => !p.active,
          },
        ]
      : [],
  );

  protected edit(pkg: Package | null): void {
    this.dialog
      .open(PackageDialogComponent, { data: { pkg } })
      .afterClosed()
      .subscribe((saved: Package | undefined) => {
        if (saved) {
          this.snack.open(`${saved.name} saved.`, undefined, { duration: 4000 });
          this.version.update((v) => v + 1);
        }
      });
  }

  private toggle(call: Observable<Package>, done: string): void {
    call.subscribe({
      next: () => {
        this.snack.open(done, undefined, { duration: 4000 });
        this.version.update((v) => v + 1);
      },
      error: (e: unknown) => this.snack.open(errorMessage(e), 'Dismiss', { duration: 8000 }),
    });
  }
}
