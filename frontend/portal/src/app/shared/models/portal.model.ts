import type { components } from '@api/schema';

type Schemas = components['schemas'];

/** `GET /api/v1/portal/customer`. */
export type PortalCustomer = Schemas['CustomerDto'];
/** `GET /api/v1/portal/lines`: every open line, the wallet balance and the pay-all amount. */
export type PortalLines = Schemas['PortalLinesDto'];
export type PortalLine = Schemas['PortalLineDto'];
export type CycleUsage = Schemas['CycleUsage'];
export type UsageDay = Schemas['UsageDay'];
export type StkRequest = Schemas['StkRequestDto'];
export type RestoreStatus = Schemas['RestoreStatus'];
export type Payment = Schemas['PaymentDto'];
export type Receipt = Schemas['ReceiptDto'];
export type LedgerEntry = Schemas['LedgerEntryDto'];
export type Money = Schemas['MoneyDto'];

/** A page of a list endpoint (conventions.md, API). */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}
