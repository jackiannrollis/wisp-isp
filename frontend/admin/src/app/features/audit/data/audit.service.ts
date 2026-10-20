import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type AuditRow = components['schemas']['AuditRow'];

/** `/api/v1/audit-log` (audit.read): newest first, filtered by action prefix, target, actor and period. */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);

  list(query: TableQuery): Observable<Page<AuditRow>> {
    return this.http.get<Page<AuditRow>>(`${API_BASE}/audit-log`, { params: listParams(query) });
  }
}
