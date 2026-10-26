import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { components } from '../../../shared/models';

export type ImportPreview = components['schemas']['CustomerImportPreviewDto'];
export type ImportWarning = components['schemas']['Warning'];
export type ImportResult = components['schemas']['CustomerImportResultDto'];
export type ImportResultRow = NonNullable<ImportResult['rows']>[number];
export type ImportBatch = components['schemas']['ImportBatch'];
export type MovedNotices = components['schemas']['MovedNotices'];

/** Options of one import: the router of the lines, and the expiry time of day for date-only paid_until values. */
export interface ImportOptions {
  routerId?: string;
  timeOfDay?: string;
}

/** `/api/v1/customer-imports` (ADR 0014): preview, import, batches; the "we've moved" SMS. */
@Injectable({ providedIn: 'root' })
export class CustomerImportsService {
  private readonly http = inject(HttpClient);

  preview(csv: string, options: ImportOptions): Observable<ImportPreview> {
    return this.http.post<ImportPreview>(`${API_BASE}/customer-imports/preview`, csv, {
      headers: { 'Content-Type': 'text/csv' },
      params: params(options),
    });
  }

  import(csv: string, options: ImportOptions, confirmation?: string): Observable<ImportResult> {
    let p = params(options);
    if (confirmation) {
      p = p.set('confirmation', confirmation);
    }
    return this.http.post<ImportResult>(`${API_BASE}/customer-imports`, csv, {
      headers: { 'Content-Type': 'text/csv' },
      params: p,
    });
  }

  batch(id: string): Observable<ImportBatch> {
    return this.http.get<ImportBatch>(`${API_BASE}/customer-imports/${encodeURIComponent(id)}`);
  }

  sendMovedNotices(importBatchId: string): Observable<MovedNotices> {
    return this.http.post<MovedNotices>(`${API_BASE}/notifications/moved-notices`, {
      importBatchId,
    });
  }
}

function params(options: ImportOptions): HttpParams {
  let p = new HttpParams();
  if (options.routerId) {
    p = p.set('routerId', options.routerId);
  }
  if (options.timeOfDay) {
    p = p.set('timeOfDay', options.timeOfDay);
  }
  return p;
}
