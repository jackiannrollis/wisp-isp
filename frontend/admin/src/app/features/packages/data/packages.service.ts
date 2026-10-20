import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Package = components['schemas']['PackageDto'];
export type PackageRequest = components['schemas']['PackageRequest'];

/** `/api/v1/packages`. A tenant has a handful of packages, so screens look names up from one page. */
@Injectable({ providedIn: 'root' })
export class PackagesService {
  private readonly http = inject(HttpClient);

  /** Every package (up to 200), for names and filter chips. Not cached across sessions: a sign-out may lead to another tenant. */
  all(): Observable<Package[]> {
    return this.http
      .get<Page<Package>>(`${API_BASE}/packages`, {
        params: new HttpParams().set('size', 200).set('sort', 'name,asc'),
      })
      .pipe(map((page) => page.items));
  }

  list(query: TableQuery): Observable<Page<Package>> {
    return this.http.get<Page<Package>>(`${API_BASE}/packages`, { params: listParams(query) });
  }

  create(request: PackageRequest): Observable<Package> {
    return this.http.post<Package>(`${API_BASE}/packages`, request);
  }

  /** Revise a package; lines keep what their current cycle was sold until they renew (S6). */
  update(id: string, request: PackageRequest): Observable<Package> {
    return this.http.put<Package>(`${API_BASE}/packages/${id}`, request);
  }

  deactivate(id: string): Observable<Package> {
    return this.http.post<Package>(`${API_BASE}/packages/${id}/deactivate`, null);
  }

  activate(id: string): Observable<Package> {
    return this.http.post<Package>(`${API_BASE}/packages/${id}/activate`, null);
  }
}
