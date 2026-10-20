import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { idempotent } from '../../../core/api/requests';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Payment = components['schemas']['PaymentDto'];
export type StkRequest = components['schemas']['StkRequestDto'];

export interface ManualPayment {
  customerId: string;
  amountMinor: number;
  channel: 'CASH' | 'BANK' | 'MPESA_PAYBILL';
  reference: string;
  receivedAt?: string;
  payerName?: string;
  note?: string;
}

/** `/api/v1/payments`: lists (finance.read), manual payments (P5) and STK pushes, with Idempotency-Keys. */
@Injectable({ providedIn: 'root' })
export class PaymentsService {
  private readonly http = inject(HttpClient);

  list(query: TableQuery): Observable<Page<Payment>> {
    return this.http.get<Page<Payment>>(`${API_BASE}/payments`, { params: listParams(query) });
  }

  recordManual(payment: ManualPayment, key: string): Observable<Payment> {
    const { amountMinor, ...rest } = payment;
    return this.http.post<Payment>(
      `${API_BASE}/payments/manual`,
      { ...rest, amount: { amount: amountMinor, currency: 'KES' } },
      idempotent(key),
    );
  }

  /** {@code phone} null pushes to the customer's own phone. */
  stkPush(
    customerId: string,
    amountMinor: number,
    phone: string | null,
    key: string,
  ): Observable<StkRequest> {
    return this.http.post<StkRequest>(
      `${API_BASE}/payments/stk`,
      { customerId, amount: { amount: amountMinor, currency: 'KES' }, phone },
      idempotent(key),
    );
  }

  stkStatus(id: string): Observable<StkRequest> {
    return this.http.get<StkRequest>(`${API_BASE}/payments/stk/${id}`);
  }
}
