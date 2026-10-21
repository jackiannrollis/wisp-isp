import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { LedgerEntry, Page, Payment, Receipt } from '../../../shared/models';

/** The customer's money history: payments, receipts and the wallet statement, newest first. */
@Injectable({ providedIn: 'root' })
export class HistoryService {
  private readonly http = inject(HttpClient);

  payments(page: number, size: number): Observable<Page<Payment>> {
    return this.http.get<Page<Payment>>(`${API_BASE}/portal/payments`, {
      params: paged(page, size),
    });
  }

  receipts(page: number, size: number): Observable<Page<Receipt>> {
    return this.http.get<Page<Receipt>>(`${API_BASE}/portal/receipts`, {
      params: paged(page, size),
    });
  }

  statement(page: number, size: number): Observable<Page<LedgerEntry>> {
    return this.http.get<Page<LedgerEntry>>(`${API_BASE}/portal/wallet/entries`, {
      params: paged(page, size),
    });
  }
}

function paged(page: number, size: number): HttpParams {
  return new HttpParams().set('page', page).set('size', size);
}
