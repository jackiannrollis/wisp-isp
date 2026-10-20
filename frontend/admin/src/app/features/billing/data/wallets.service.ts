import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { idempotent } from '../../../core/api/requests';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Wallet = components['schemas']['WalletDto'];
export type LedgerEntry = components['schemas']['LedgerEntryDto'];
export type Receipt = components['schemas']['ReceiptDto'];

/** A customer's wallet, statement and receipts (finance.read), and staff adjustments (billing.adjust, B1). */
@Injectable({ providedIn: 'root' })
export class WalletsService {
  private readonly http = inject(HttpClient);

  wallet(customerId: string): Observable<Wallet> {
    return this.http.get<Wallet>(`${API_BASE}/customers/${customerId}/wallet`);
  }

  entries(customerId: string, query: TableQuery): Observable<Page<LedgerEntry>> {
    return this.http.get<Page<LedgerEntry>>(`${API_BASE}/customers/${customerId}/wallet/entries`, {
      params: listParams(query),
    });
  }

  receipts(customerId: string, query: TableQuery): Observable<Page<Receipt>> {
    return this.http.get<Page<Receipt>>(`${API_BASE}/receipts`, {
      params: listParams(query).set('customerId', customerId),
    });
  }

  /** {@code amountMinor} positive credits, negative debits; always with a reason. */
  adjust(
    customerId: string,
    amountMinor: number,
    reason: string,
    key: string,
  ): Observable<LedgerEntry> {
    return this.http.post<LedgerEntry>(
      `${API_BASE}/customers/${customerId}/wallet/adjustments`,
      { amount: { amount: amountMinor, currency: 'KES' }, reason },
      idempotent(key),
    );
  }
}
