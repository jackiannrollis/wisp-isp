import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { idempotent } from '../../../core/api/requests';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Line = components['schemas']['SubscriptionDto'];
export type LineOpened = components['schemas']['LineOpenedDto'];
export type PppoeCredential = components['schemas']['PppoeCredentialDto'];

const LINES = `${API_BASE}/subscriptions`;

/**
 * `/api/v1/subscriptions`: PPPoE lines. Display status and access profile come from the API (S1).
 * Lifecycle changes carry an Idempotency-Key (they change access); a new password is shown once and
 * never replayed, so its requests carry none (ADR 0009).
 */
@Injectable({ providedIn: 'root' })
export class LinesService {
  private readonly http = inject(HttpClient);

  list(query: TableQuery): Observable<Page<Line>> {
    return this.http.get<Page<Line>>(LINES, { params: listParams(query) });
  }

  /** A customer's lines (a customer has a handful). */
  forCustomer(customerId: string): Observable<Line[]> {
    return this.http
      .get<Page<Line>>(LINES, {
        params: new HttpParams()
          .set('customerId', customerId)
          .set('size', 200)
          .set('sort', 'createdAt,asc'),
      })
      .pipe(map((page) => page.items));
  }

  open(customerId: string, packageId: string, routerId: string): Observable<LineOpened> {
    return this.http.post<LineOpened>(LINES, { customerId, packageId, routerId });
  }

  newPassword(id: string): Observable<PppoeCredential> {
    return this.http.post<PppoeCredential>(`${LINES}/${id}/pppoe-password`, null);
  }

  pause(id: string, pauseUntil: string, key: string): Observable<Line> {
    return this.http.post<Line>(`${LINES}/${id}/pause`, { pauseUntil }, idempotent(key));
  }

  resume(id: string, key: string): Observable<Line> {
    return this.http.post<Line>(`${LINES}/${id}/resume`, null, idempotent(key));
  }

  suspend(id: string, reason: string, key: string): Observable<Line> {
    return this.http.post<Line>(`${LINES}/${id}/suspend`, { reason }, idempotent(key));
  }

  liftSuspension(id: string, reason: string, key: string): Observable<Line> {
    return this.http.post<Line>(`${LINES}/${id}/lift-suspension`, { reason }, idempotent(key));
  }

  terminate(id: string, reason: string, key: string): Observable<Line> {
    return this.http.post<Line>(`${LINES}/${id}/terminate`, { reason }, idempotent(key));
  }

  /** A PENDING line, never paid, closed before activation (customers.write; docs/04). */
  cancel(id: string, reason: string, key: string): Observable<Line> {
    return this.http.post<Line>(`${LINES}/${id}/cancel`, { reason }, idempotent(key));
  }

  changePackage(
    id: string,
    packageId: string,
    timing: 'NOW' | 'NEXT_RENEWAL',
    key: string,
  ): Observable<Line> {
    return this.http.post<Line>(`${LINES}/${id}/package`, { packageId, timing }, idempotent(key));
  }
}
