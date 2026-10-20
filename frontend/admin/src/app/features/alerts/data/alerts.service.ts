import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Alert = components['schemas']['AlertView'];
export type PhoneFlag = components['schemas']['PhoneFlagView'];
export type AlertState = 'OPEN' | 'CLEARED';

/** `/api/v1/notifications/alerts` and the numbers the SMS provider refused for good. */
@Injectable({ providedIn: 'root' })
export class AlertsService {
  private readonly http = inject(HttpClient);

  list(state: AlertState, query: TableQuery): Observable<Page<Alert>> {
    return this.http.get<Page<Alert>>(`${API_BASE}/notifications/alerts`, {
      params: listParams(query).set('state', state),
    });
  }

  /** How many alerts are open, for the navigation badge. */
  openCount(): Observable<number> {
    return this.list('OPEN', { page: 0, size: 1, filters: {} }).pipe(map((page) => page.total));
  }

  phoneFlags(): Observable<PhoneFlag[]> {
    return this.http.get<PhoneFlag[]>(`${API_BASE}/notifications/phone-flags`);
  }

  clearPhoneFlag(phone: string): Observable<void> {
    return this.http.delete<void>(
      `${API_BASE}/notifications/phone-flags/${encodeURIComponent(phone)}`,
    );
  }
}
