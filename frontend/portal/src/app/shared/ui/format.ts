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

/** 788645 seconds → `9d 3h`; under an hour → `12m`. */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) {
    return '—';
  }
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  if (days > 0) {
    return `${days}d ${hours}h`;
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  return `${minutes}m`;
}

/** How long ago, roughly: `just now`, `4 min ago`, `3 h ago`, `2 days ago`. */
export function formatAge(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) {
    return 'never';
  }
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (seconds < 60) {
    return 'just now';
  }
  if (seconds < 3_600) {
    return `${Math.floor(seconds / 60)} min ago`;
  }
  if (seconds < 86_400) {
    return `${Math.floor(seconds / 3_600)} h ago`;
  }
  const days = Math.floor(seconds / 86_400);
  return `${days} ${days === 1 ? 'day' : 'days'} ago`;
}
