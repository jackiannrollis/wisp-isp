import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { components } from '../../../shared/models';

export type Dashboard = components['schemas']['DashboardDto'];
export type DayCollected = components['schemas']['DayCollected'];

/**
 * `/api/v1/dashboard`: tenant-local days, both included; `revenue` is absent without finance.read and
 * `suspense` without finance.read or payments.assign.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);

  get(from: string, to: string): Observable<Dashboard> {
    return this.http.get<Dashboard>(`${API_BASE}/dashboard`, {
      params: new HttpParams().set('from', from).set('to', to),
    });
  }
}
