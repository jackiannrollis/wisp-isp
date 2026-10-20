import type { TablerIcon } from '@tabler/icons-angular';
import { Observable } from 'rxjs';

/** A page of a list endpoint (conventions.md, API: items, page, size, total). */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** What the table asks its data source for; filters and search are sent as query parameters. */
export interface TableQuery {
  page: number;
  size: number;
  /** `field,asc` or `field,desc`, as the API's `sort` parameter takes it. */
  sort?: string;
  filters: Record<string, string>;
  q?: string;
}

export type TableFetch<T> = (query: TableQuery) => Observable<Page<T>>;

export interface ColumnDef<T> {
  key: string;
  header: string;
  /** Text for the default cell and for CSV export. A custom cell template may show more. */
  value: (row: T) => string | number | null | undefined;
  /** The API's sort field; absent means the column cannot be sorted. */
  sortKey?: string;
  /** Hidden until the user shows it from the column menu. */
  hiddenByDefault?: boolean;
  align?: 'start' | 'end';
}

export interface FilterOption {
  value: string;
  label: string;
}

/** A filter shown as a row of chips; at most one option is selected, none means "any". */
export interface FilterDef {
  key: string;
  label: string;
  options: FilterOption[];
}

export interface RowAction<T> {
  label: string;
  icon: TablerIcon;
  run: (row: T) => void;
  visible?: (row: T) => boolean;
}

export interface SortDef {
  key: string;
  direction: 'asc' | 'desc';
}
