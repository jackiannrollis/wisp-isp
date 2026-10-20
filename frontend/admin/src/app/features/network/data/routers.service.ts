import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { Page } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Router = components['schemas']['RouterDto'];

/** `/api/v1/routers`. */
@Injectable({ providedIn: 'root' })
export class RoutersService {
  private readonly http = inject(HttpClient);

  /** Every router (up to 200), for names and filter chips. Not cached across sessions: a sign-out may lead to another tenant. */
  all(): Observable<Router[]> {
    return this.http
      .get<Page<Router>>(`${API_BASE}/routers`, {
        params: new HttpParams().set('size', 200).set('sort', 'name,asc'),
      })
      .pipe(map((page) => page.items));
  }
}
