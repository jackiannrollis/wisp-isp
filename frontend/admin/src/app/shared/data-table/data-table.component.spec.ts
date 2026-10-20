import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { CellDirective } from './cell.directive';
import { DataTableComponent } from './data-table.component';
import { ColumnDef, FilterDef, Page, TableQuery } from './data-table.types';

interface Row {
  id: string;
  name: string;
  status: string;
}

@Component({
  imports: [DataTableComponent, CellDirective],
  template: `
    <app-data-table
      tableId="spec"
      [columns]="columns"
      [filters]="filters"
      [fetch]="fetch"
      [defaultSort]="{ key: 'name', direction: 'asc' }"
    >
      <ng-template appCell="status" let-row
        ><b class="custom">{{ row.status }}!</b></ng-template
      >
    </app-data-table>
  `,
})
class HostComponent {
  readonly queries = signal<TableQuery[]>([]);
  readonly columns: ColumnDef<Row>[] = [
    { key: 'name', header: 'Name', value: (r) => r.name, sortKey: 'name' },
    { key: 'status', header: 'Status', value: (r) => r.status },
    { key: 'id', header: 'Id', value: (r) => r.id, hiddenByDefault: true },
  ];
  readonly filters: FilterDef[] = [
    { key: 'status', label: 'Status', options: [{ value: 'ACTIVE', label: 'Active' }] },
  ];
  readonly fetch = (query: TableQuery) => {
    this.queries.update((q) => [...q, query]);
    const page: Page<Row> = {
      items: [{ id: 'r1', name: 'Wanjiku', status: 'ACTIVE' }],
      page: query.page,
      size: query.size,
      total: 1,
    };
    return of(page);
  };
}

describe('DataTableComponent', () => {
  beforeEach(() => {
    try {
      localStorage.removeItem('corebalt.table.spec.hidden');
    } catch {
      // no storage in this environment
    }
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideRouter([])] });
  });

  it('asks the server for the first page with the default sort, and renders custom cells', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.queries()[0]).toEqual({
      page: 0,
      size: 50,
      sort: 'name,asc',
      filters: {},
      q: undefined,
    });
    const element = fixture.nativeElement as HTMLElement;
    const headers = Array.from(element.querySelectorAll('th')).map((th) => th.textContent?.trim());
    expect(headers).toEqual(['Name', 'Status']);
    expect(element.querySelector('.custom')?.textContent).toBe('ACTIVE!');
  });

  it('sends a chosen filter chip and starts again at the first page', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLElement>('mat-chip-option [role=option]')
      ?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    const last = fixture.componentInstance.queries().at(-1);
    expect(last?.filters).toEqual({ status: 'ACTIVE' });
    expect(last?.page).toBe(0);
  });
});
