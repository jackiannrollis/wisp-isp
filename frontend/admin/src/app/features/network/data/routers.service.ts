import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Router = components['schemas']['RouterDto'];
export type RouterWithSecret = components['schemas']['RouterSecretDto'];
export type RouterSessions = components['schemas']['RouterSessionsDto'];

const ROUTERS = `${API_BASE}/routers`;

/**
 * `/api/v1/routers`. The API never talks to a router (ADR 0011): a poll request goes to the tunnel
 * worker, and the session snapshot is what the worker last read. Secrets are write-only (T4).
 */
@Injectable({ providedIn: 'root' })
export class RoutersService {
  private readonly http = inject(HttpClient);

  list(query: TableQuery): Observable<Page<Router>> {
    return this.http.get<Page<Router>>(ROUTERS, { params: listParams(query) });
  }

  /** Every router (up to 200), for names and filter chips. Not cached: a sign-out may lead to another tenant. */
  all(): Observable<Router[]> {
    return this.http
      .get<Page<Router>>(ROUTERS, {
        params: new HttpParams().set('size', 200).set('sort', 'name,asc'),
      })
      .pipe(map((page) => page.items));
  }

  get(id: string): Observable<Router> {
    return this.http.get<Router>(`${ROUTERS}/${id}`);
  }

  sessions(id: string): Observable<RouterSessions> {
    return this.http.get<RouterSessions>(`${ROUTERS}/${id}/sessions`);
  }

  /** The RADIUS secret in the answer is shown once and never again. */
  register(name: string, tunnelIp: string): Observable<RouterWithSecret> {
    return this.http.post<RouterWithSecret>(ROUTERS, { name, tunnelIp });
  }

  newRadiusSecret(id: string): Observable<RouterWithSecret> {
    return this.http.post<RouterWithSecret>(`${ROUTERS}/${id}/radius-secret`, null);
  }

  setRestCredential(id: string, username: string, password: string): Observable<Router> {
    return this.http.put<Router>(`${ROUTERS}/${id}/rest-credential`, { username, password });
  }

  removeRestCredential(id: string): Observable<Router> {
    return this.http.delete<Router>(`${ROUTERS}/${id}/rest-credential`);
  }

  /** Asks the tunnel worker to poll now; the result shows a few seconds later. */
  pollNow(id: string): Observable<void> {
    return this.http.post<void>(`${ROUTERS}/${id}/poll`, null);
  }
}
