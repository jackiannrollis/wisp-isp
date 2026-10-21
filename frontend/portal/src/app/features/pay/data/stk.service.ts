import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { Money, StkRequest } from '../../../shared/models';

/** Pay Now: an M-Pesa STK Push to the customer's own registered phone (docs/05, P6). */
@Injectable({ providedIn: 'root' })
export class StkService {
  private readonly http = inject(HttpClient);

  /** A money write: the same key for every retry of one press, so the phone is pushed once. */
  payNow(amount: Money, idempotencyKey: string): Observable<StkRequest> {
    return this.http.post<StkRequest>(
      `${API_BASE}/portal/stk`,
      { amount },
      { headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }) },
    );
  }

  request(id: string): Observable<StkRequest> {
    return this.http.get<StkRequest>(`${API_BASE}/portal/stk/${id}`);
  }
}
