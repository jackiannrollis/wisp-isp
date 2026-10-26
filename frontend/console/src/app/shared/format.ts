const DATE_TIME = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

/** "26 Oct 2026, 21:35" in the viewer's time zone, or a dash. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : DATE_TIME.format(date);
}

/** A cutover window's end, or "closed" when there is none or it has passed. */
export function formatDateTimeOrClosed(iso: string | null | undefined, now = new Date()): string {
  if (!iso || new Date(iso).getTime() <= now.getTime()) {
    return 'closed';
  }
  return `open until ${formatDateTime(iso)}`;
}

/** ACTIVE → Active, NOT_MONITORED → Not monitored. */
export function statusLabel(status: string | null | undefined): string {
  if (!status) {
    return '—';
  }
  const words = status.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** The value of a datetime-local input for an instant, in the viewer's time zone. */
export function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** The longest cutover window the console opens (ADR 0014); the server enforces it too. */
export const MAX_CUTOVER_DAYS = 30;

/**
 * A cutover window's end from the datetime-local input: an ISO instant, or why it cannot be used (in
 * the past, or more than {@link MAX_CUTOVER_DAYS} days ahead of {@code now}).
 */
export function cutoverUntil(local: string, now: Date): { until?: string; problem?: string } {
  const date = new Date(local);
  if (!local || Number.isNaN(date.getTime())) {
    return { problem: 'Choose a date and time.' };
  }
  if (date.getTime() <= now.getTime()) {
    return { problem: 'The end must be in the future.' };
  }
  if (date.getTime() > now.getTime() + MAX_CUTOVER_DAYS * 24 * 60 * 60 * 1000) {
    return { problem: `At most ${MAX_CUTOVER_DAYS} days ahead.` };
  }
  return { until: date.toISOString() };
}
