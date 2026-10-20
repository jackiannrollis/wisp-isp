import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Customer = components['schemas']['CustomerDto'];
export type CustomerRequest = components['schemas']['CustomerRequest'];

/** `/api/v1/customers`. */
@Injectable({ providedIn: 'root' })
export class CustomersService {
  private readonly http = inject(HttpClient);

  list(query: TableQuery): Observable<Page<Customer>> {
    return this.http.get<Page<Customer>>(`${API_BASE}/customers`, { params: listParams(query) });
  }

  get(id: string): Observable<Customer> {
    return this.http.get<Customer>(`${API_BASE}/customers/${encodeURIComponent(id)}`);
  }

  create(request: CustomerRequest): Observable<Customer> {
    return this.http.post<Customer>(`${API_BASE}/customers`, request);
  }

  update(id: string, request: CustomerRequest): Observable<Customer> {
    return this.http.put<Customer>(`${API_BASE}/customers/${encodeURIComponent(id)}`, request);
  }

  archive(id: string): Observable<Customer> {
    return this.http.post<Customer>(
      `${API_BASE}/customers/${encodeURIComponent(id)}/archive`,
      null,
    );
  }
}
