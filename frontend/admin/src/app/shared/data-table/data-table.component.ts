import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  contentChildren,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatMenuModule } from '@angular/material/menu';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import {
  IconAlertTriangle,
  IconColumns3,
  IconDotsVertical,
  IconDownload,
  IconInbox,
  IconRefresh,
  IconSearch,
  IconX,
  TablerIconComponent,
} from '@tabler/icons-angular';
import { firstValueFrom } from 'rxjs';

import { CellDirective } from './cell.directive';
import { downloadCsv, toCsv } from './csv';
import {
  ColumnDef,
  FilterDef,
  RowAction,
  SortDef,
  TableFetch,
  TableQuery,
} from './data-table.types';

/** The most rows one CSV export reads (pages of 200). */
export const EXPORT_LIMIT = 10_000;
const EXPORT_PAGE = 200;

/**
 * The shared list table (ADR 0002 amendment; conventions.md: tables page, sort and filter on the
 * server, never load everything): server paging and sorting, filter chips, search, a column menu
 * remembered per browser, row actions and CSV export of the current filters and visible columns.
 */
@Component({
  selector: 'app-data-table',
  imports: [
    NgTemplateOutlet,
    MatTableModule,
    MatSortModule,
    MatPaginatorModule,
    MatProgressBarModule,
    MatChipsModule,
    MatMenuModule,
    MatCheckboxModule,
    MatButtonModule,
    MatTooltipModule,
    TablerIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './data-table.component.html',
  styleUrl: './data-table.component.scss',
})
export class DataTableComponent<T> {
  private readonly router = inject(Router);

  /** Names the column preferences in this browser. */
  readonly tableId = input.required<string>();
  readonly columns = input.required<ColumnDef<T>[]>();
  readonly fetch = input.required<TableFetch<T>>();
  readonly filters = input<FilterDef[]>([]);
  /** Chip selections to start with (for example a status from the URL); the user can change them. */
  readonly initialFilters = input<Record<string, string>>({});
  /** Filters set from outside (for example a customer from the URL), shown as removable chips. */
  readonly presetFilters = input<Record<string, { value: string; label: string }>>({});
  readonly searchPlaceholder = input<string | null>(null);
  readonly actions = input<RowAction<T>[]>([]);
  readonly defaultSort = input<SortDef | null>(null);
  readonly exportName = input<string | null>(null);
  readonly rowLink = input<((row: T) => string) | null>(null);
  readonly emptyText = input('Nothing to show.');
  /** Singular and plural noun for the count in the toolbar, e.g. ['customer', 'customers']. */
  readonly itemLabel = input<[string, string]>(['result', 'results']);
  /** Change it to make the table read its current page again (after the page changed the data). */
  readonly refresh = input(0);

  private readonly cells = contentChildren(CellDirective);
  protected readonly cellTemplates = computed(
    () => new Map(this.cells().map((cell) => [cell.appCell(), cell.template])),
  );

  protected readonly skeletonRows = [92, 78, 86, 64, 80];

  protected readonly icons = {
    alert: IconAlertTriangle,
    empty: IconInbox,
    columns: IconColumns3,
    download: IconDownload,
    refresh: IconRefresh,
    more: IconDotsVertical,
    search: IconSearch,
    clear: IconX,
  };
  protected readonly pageSizes = [25, 50, 100, 200];

  protected readonly page = signal(0);
  protected readonly size = signal(50);
  protected readonly sort = signal<SortDef | null>(null);
  protected readonly selected = linkedSignal<Record<string, string>>(() => ({
    ...this.initialFilters(),
  }));
  protected readonly removedPresets = signal<Set<string>>(new Set());
  protected readonly q = signal('');
  protected readonly exporting = signal(false);
  private readonly hiddenOverride = signal<Set<string> | null>(null);

  protected readonly hasFilters = computed(
    () => Object.keys(this.query().filters).length > 0 || !!this.query().q,
  );
  protected readonly effectiveSort = computed(() => this.sort() ?? this.defaultSort());
  protected readonly activePresets = computed(() =>
    Object.entries(this.presetFilters()).filter(([key]) => !this.removedPresets().has(key)),
  );

  protected readonly query = computed<TableQuery>(() => {
    const sort = this.effectiveSort();
    const filters: Record<string, string> = { ...this.selected() };
    for (const [key, preset] of this.activePresets()) {
      filters[key] = preset.value;
    }
    return {
      page: this.page(),
      size: this.size(),
      sort: sort ? `${sort.key},${sort.direction}` : undefined,
      filters,
      q: this.q().trim() || undefined,
    };
  });

  protected readonly data = rxResource({
    params: () => ({ query: this.query(), fetch: this.fetch(), refresh: this.refresh() }),
    stream: ({ params }) => params.fetch(params.query),
  });

  protected readonly hidden = computed(
    () =>
      this.hiddenOverride() ??
      readHidden(this.tableId()) ??
      new Set(
        this.columns()
          .filter((c) => c.hiddenByDefault)
          .map((c) => c.key),
      ),
  );
  protected readonly visibleColumns = computed(() =>
    this.columns().filter((c) => !this.hidden().has(c.key)),
  );
  protected readonly displayed = computed(() => {
    const keys = this.visibleColumns().map((c) => c.key);
    return this.actions().length ? [...keys, '__actions'] : keys;
  });

  protected onSort(sort: Sort): void {
    const column = this.columns().find((c) => c.key === sort.active);
    this.sort.set(
      sort.direction && column?.sortKey ? { key: column.sortKey, direction: sort.direction } : null,
    );
    this.page.set(0);
  }

  protected onPage(event: PageEvent): void {
    this.page.set(event.pageIndex);
    this.size.set(event.pageSize);
  }

  protected choose(filter: string, value: string | null): void {
    this.selected.update((current) => {
      const next = { ...current };
      if (value === null || current[filter] === value) {
        delete next[filter];
      } else {
        next[filter] = value;
      }
      return next;
    });
    this.page.set(0);
  }

  protected removePreset(key: string): void {
    this.removedPresets.update((removed) => new Set([...removed, key]));
    this.page.set(0);
  }

  protected search(text: string): void {
    this.q.set(text);
    this.page.set(0);
  }

  protected toggleColumn(key: string): void {
    const hidden = new Set(this.hidden());
    if (hidden.has(key)) {
      hidden.delete(key);
    } else if (this.visibleColumns().length > 1) {
      hidden.add(key);
    }
    this.hiddenOverride.set(hidden);
    writeHidden(this.tableId(), hidden);
  }

  protected sortHeader(column: ColumnDef<T>): string {
    return column.key;
  }

  protected activeSortColumn(): string {
    const sort = this.effectiveSort();
    return this.columns().find((c) => c.sortKey === sort?.key)?.key ?? '';
  }

  protected visibleActions(row: T): RowAction<T>[] {
    return this.actions().filter((action) => !action.visible || action.visible(row));
  }

  protected open(row: T): void {
    const link = this.rowLink();
    if (link) {
      void this.router.navigateByUrl(link(row));
    }
  }

  /** Every page of the current filters and sort, up to {@link EXPORT_LIMIT} rows, visible columns only. */
  protected async exportCsv(): Promise<void> {
    this.exporting.set(true);
    try {
      const columns = this.visibleColumns();
      const rows: T[] = [];
      for (let page = 0; rows.length < EXPORT_LIMIT; page++) {
        const result = await firstValueFrom(
          this.fetch()({ ...this.query(), page, size: EXPORT_PAGE }),
        );
        rows.push(...result.items);
        if (result.items.length < EXPORT_PAGE || rows.length >= result.total) {
          break;
        }
      }
      const csv = toCsv(
        columns.map((c) => c.header),
        rows.slice(0, EXPORT_LIMIT).map((row) => columns.map((c) => c.value(row))),
      );
      downloadCsv(
        `${this.exportName() ?? this.tableId()}-${new Date().toISOString().slice(0, 10)}.csv`,
        csv,
      );
    } finally {
      this.exporting.set(false);
    }
  }
}

function key(tableId: string): string {
  return `corebalt.table.${tableId}.hidden`;
}

/** Column choices are a per-browser convenience; unavailable storage just means the defaults. */
function readHidden(tableId: string): Set<string> | null {
  try {
    const stored = localStorage.getItem(key(tableId));
    return stored ? new Set(JSON.parse(stored) as string[]) : null;
  } catch {
    return null;
  }
}

function writeHidden(tableId: string, hidden: Set<string>): void {
  try {
    localStorage.setItem(key(tableId), JSON.stringify([...hidden]));
  } catch {
    // Private window or blocked storage: the choice lasts for this page only.
  }
}
