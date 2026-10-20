import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Line = components['schemas']['SubscriptionDto'];

/** `/api/v1/subscriptions`: PPPoE lines; display status and access profile come from the API (S1). */
@Injectable({ providedIn: 'root' })
export class LinesService {
  private readonly http = inject(HttpClient);

  list(query: TableQuery): Observable<Page<Line>> {
    return this.http.get<Page<Line>>(`${API_BASE}/subscriptions`, { params: listParams(query) });
  }
}
