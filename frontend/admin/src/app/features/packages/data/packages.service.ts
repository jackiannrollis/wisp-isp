import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { Page } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Package = components['schemas']['PackageDto'];

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
}
