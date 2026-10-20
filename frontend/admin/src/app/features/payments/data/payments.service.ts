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
export type SuspenseItem = components['schemas']['SuspenseItem'];
export type ReconciliationRun = components['schemas']['RunDto'];

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

  /** Unmatched payments, newest first, each with its likely-payer shortlist (P3). */
  suspense(query: TableQuery): Observable<Page<SuspenseItem>> {
    return this.http.get<Page<SuspenseItem>>(`${API_BASE}/payments/suspense`, {
      params: listParams(query),
    });
  }

  /** Assigns an unmatched payment to a customer; billing credits the wallet from it (P3, P4). */
  assign(paymentId: string, customerId: string, key: string): Observable<Payment> {
    return this.http.post<Payment>(
      `${API_BASE}/payments/${paymentId}/assign`,
      { customerId },
      idempotent(key),
    );
  }

  reconciliations(query: TableQuery): Observable<Page<ReconciliationRun>> {
    return this.http.get<Page<ReconciliationRun>>(`${API_BASE}/payments/reconciliations`, {
      params: listParams(query),
    });
  }

  reconciliation(id: string): Observable<ReconciliationRun> {
    return this.http.get<ReconciliationRun>(`${API_BASE}/payments/reconciliations/${id}`);
  }

  /** Compares an M-Pesa statement export (CSV) with recorded payments, both directions. */
  reconcile(csv: string): Observable<ReconciliationRun> {
    return this.http.post<ReconciliationRun>(`${API_BASE}/payments/reconciliations`, csv, {
      headers: { 'Content-Type': 'text/csv' },
    });
  }

  /** Asks Daraja about a transaction the platform lacks; a completed one arrives as a payment. */
  investigate(transactionId: string): Observable<{ conversationId: string }> {
    return this.http.post<{ conversationId: string }>(`${API_BASE}/payments/transaction-status`, {
      transactionId,
    });
  }

  stkStatus(id: string): Observable<StkRequest> {
    return this.http.get<StkRequest>(`${API_BASE}/payments/stk/${id}`);
  }
}
