/** Money as the API sends it: integer minor units and an ISO currency (conventions.md, API; B2). */
export interface MoneyValue {
  amount?: number;
  currency?: string;
}

const formatters = new Map<string, Intl.NumberFormat>();

/**
 * `{ amount: 250000, currency: 'KES' }` → `KES 2,500.00`. Only display: the minor units are divided
 * here for showing, never for arithmetic. Never coloured red or green; a debit carries its minus sign.
 */
export function formatMoney(money: MoneyValue | null | undefined): string {
  if (!money || money.amount === undefined || money.amount === null) {
    return '—';
  }
  const currency = money.currency ?? 'KES';
  let formatter = formatters.get(currency);
  if (!formatter) {
    formatter = new Intl.NumberFormat('en-KE', {
      style: 'currency',
      currency,
      currencyDisplay: 'code',
      minimumFractionDigits: 2,
    });
    formatters.set(currency, formatter);
  }
  return formatter.format(money.amount / 100).replace(/\p{Zs}/gu, ' ');
}

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];

/** 8589935592 → `8.6 GB` (decimal units, as ISPs sell data). */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) {
    return '—';
  }
  let value = bytes;
  let unit = 0;
  while (value >= 1000 && unit < UNITS.length - 1) {
    value /= 1000;
    unit++;
  }
  return `${unit === 0 ? value : value.toFixed(value >= 100 ? 0 : 1)} ${UNITS[unit]}`;
}

const dateTime = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

/** ISO instant → `18 Nov 2026, 21:52` in the browser's time zone. */
export function formatDateTime(iso: string | null | undefined): string {
  return iso ? dateTime.format(new Date(iso)) : '—';
}
