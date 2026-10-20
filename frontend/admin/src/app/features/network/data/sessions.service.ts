import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type OpenSession = components['schemas']['OpenSessionDto'];

/** `/api/v1/sessions`: every open session of the tenant, from radacct beside each router's last poll. */
@Injectable({ providedIn: 'root' })
export class SessionsService {
  private readonly http = inject(HttpClient);

  list(query: TableQuery): Observable<Page<OpenSession>> {
    return this.http.get<Page<OpenSession>>(`${API_BASE}/sessions`, { params: listParams(query) });
  }
}
