import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { PortalLines, RestoreStatus, UsageDay } from '../../../shared/models';

/** The customer's own lines (`/api/v1/portal/lines`): status, package, expiry and usage. */
@Injectable({ providedIn: 'root' })
export class LinesService {
  private readonly http = inject(HttpClient);

  lines(): Observable<PortalLines> {
    return this.http.get<PortalLines>(`${API_BASE}/portal/lines`);
  }

  /** Daily usage, tenant-local days; the last 30 by default (N7: from the daily roll-up only). */
  usage(lineId: string): Observable<UsageDay[]> {
    return this.http.get<UsageDay[]>(`${API_BASE}/portal/lines/${lineId}/usage`);
  }

  /** Whether the line's access is restored since a payment (docs/06 Restricted mode). */
  restoreStatus(lineId: string, since: string): Observable<RestoreStatus> {
    return this.http.get<RestoreStatus>(`${API_BASE}/portal/lines/${lineId}/restore-status`, {
      params: new HttpParams().set('since', since),
    });
  }
}
