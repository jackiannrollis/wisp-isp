/** `10 Mbit/s`, `2.5 Mbit/s` or `512 kbit/s` from kbit/s, as packages store speeds. */
export function formatSpeed(kbps: number | null | undefined): string {
  if (kbps === null || kbps === undefined) {
    return '—';
  }
  if (kbps < 1000) {
    return `${kbps} kbit/s`;
  }
  return `${trim(kbps / 1000)} Mbit/s`;
}

/** Download and upload as staff and customers read them: `10 / 5 Mbit/s`. */
export function formatSpeeds(downloadKbps?: number, uploadKbps?: number): string {
  if (!downloadKbps || !uploadKbps) {
    return '—';
  }
  if (downloadKbps >= 1000 && uploadKbps >= 1000) {
    return `${trim(downloadKbps / 1000)} / ${trim(uploadKbps / 1000)} Mbit/s`;
  }
  return `${formatSpeed(downloadKbps)} / ${formatSpeed(uploadKbps)}`;
}

/** A package's period from minutes: `30 days`, `1 day`, `12 hours`, `90 minutes`. */
export function formatPeriod(minutes: number | null | undefined): string {
  if (!minutes) {
    return '—';
  }
  if (minutes % 1440 === 0) {
    return plural(minutes / 1440, 'day');
  }
  if (minutes % 60 === 0) {
    return plural(minutes / 60, 'hour');
  }
  return plural(minutes, 'minute');
}

/** Mbit/s typed by staff (up to 3 decimals) to whole kbit/s; null when it is not a positive number. */
export function mbpsToKbps(text: string): number | null {
  const match = /^\s*(\d{1,5})(?:\.(\d{1,3}))?\s*$/.exec(text);
  if (!match) {
    return null;
  }
  const kbps = Number(match[1]) * 1000 + Number((match[2] ?? '').padEnd(3, '0'));
  return kbps > 0 ? kbps : null;
}

/** kbit/s back to the Mbit/s text the form shows. */
export function kbpsToMbps(kbps: number | null | undefined): string {
  return kbps ? String(trim(kbps / 1000)) : '';
}

/** The period split for the form: whole days when it divides, otherwise hours. */
export function splitPeriod(minutes: number): { amount: number; unit: 'days' | 'hours' } {
  return minutes % 1440 === 0
    ? { amount: minutes / 1440, unit: 'days' }
    : { amount: Math.max(1, Math.round(minutes / 60)), unit: 'hours' };
}

function trim(value: number): number {
  return Math.round(value * 1000) / 1000;
}

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}
