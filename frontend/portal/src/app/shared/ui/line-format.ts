import { CycleUsage, PortalLine } from '../models';
import { formatBytes, formatDateTime } from './format';

/**
 * What a customer reads about a line, from the API's displayStatus, accessReason and dates only: the
 * portal never decides access itself (conventions.md, Frontend: no business rules).
 */
export function lineSummary(line: PortalLine): string {
  const expires = formatDateTime(line.expiresAt);
  switch (line.displayStatus) {
    case 'ACTIVE':
      return `Paid until ${expires}.`;
    case 'EXPIRING':
      return `Expires ${expires}. Renew to stay online.`;
    case 'GRACE':
      return `Expired ${expires}. You are still online for a short time: pay to keep the connection.`;
    case 'THROTTLED':
      return line.accessReason === 'FAIR_USE'
        ? `You have used this period's fair-use allowance, so the speed is reduced until ${expires}.`
        : `Expired ${expires}. The speed is reduced until you pay.`;
    case 'CAPPED':
      return `This period's data is used up. Renew to get back online.`;
    case 'RESTRICTED':
      return `Expired ${expires}. Pay to get back online.`;
    case 'PAUSED':
      return line.pauseUntil
        ? `Paused until ${formatDateTime(line.pauseUntil)}.`
        : 'Paused. Your remaining days wait for you.';
    case 'SUSPENDED':
      return 'Suspended by your provider. Please contact them.';
    case 'PENDING':
      return 'Not connected yet. Pay the first period to get online.';
    default:
      return '';
  }
}

/** `10 Mbps down · 5 Mbps up` from the package's kilobits per second. */
export function speedText(line: PortalLine): string {
  if (!line.downloadKbps || !line.uploadKbps) {
    return '';
  }
  return `${mbps(line.downloadKbps)} down · ${mbps(line.uploadKbps)} up`;
}

function mbps(kbps: number): string {
  return kbps >= 1000 ? `${+(kbps / 1000).toFixed(1)} Mbps` : `${kbps} kbps`;
}

/** The allowance a line is metered against this cycle: the data cap when there is one. */
export interface Allowance {
  label: string;
  limitBytes: number;
  usedBytes: number;
  /** Used share, 0 to 1 (never above, even past the limit). */
  share: number;
  reached: boolean;
}

export function allowance(usage: CycleUsage | null | undefined): Allowance | null {
  const used = usage?.usedBytes ?? 0;
  if (usage?.dataCapBytes) {
    return {
      label: 'Data',
      limitBytes: usage.dataCapBytes,
      usedBytes: used,
      share: Math.min(1, used / usage.dataCapBytes),
      reached: !!usage.dataCapReached,
    };
  }
  if (usage?.fairUseBytes) {
    return {
      label: 'Fair use',
      limitBytes: usage.fairUseBytes,
      usedBytes: used,
      share: Math.min(1, used / usage.fairUseBytes),
      reached: !!usage.fairUseExceeded,
    };
  }
  return null;
}

/** `45.2 GB of 100 GB` */
export function allowanceText(a: Allowance): string {
  return `${formatBytes(a.usedBytes)} of ${formatBytes(a.limitBytes)}`;
}
