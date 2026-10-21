import {
  IconAlertTriangle,
  IconClockExclamation,
  IconDatabaseOff,
  IconGauge,
  IconHourglassLow,
  IconInbox,
  IconLock,
  IconUserPause,
  IconRouterOff,
  type TablerIcon,
} from '@tabler/icons-angular';

import { formatMoney } from '../../shared/ui/format';
import type { Alert } from '../alerts/data/alerts.service';
import type { Dashboard } from './data/dashboard.service';

/** One row of the dashboard's action queue: what is wrong and the page that fixes it. */
export interface AttentionItem {
  key: string;
  tone: 'error' | 'warning';
  icon: TablerIcon;
  text: string;
  detail?: string;
  action: string;
  link: string;
  queryParams?: Record<string, string>;
}

/**
 * The action queue, most urgent first: routers down and open alerts, then money waiting in suspense,
 * then lines that lost or are about to lose service, or are slowed by fair use. Only rows with something to do appear; the
 * counts are the API's (display status is derived server-side, S1).
 */
export function attentionItems(
  d: Dashboard | undefined,
  alerts: Alert[] | undefined,
): AttentionItem[] {
  if (!d) {
    return [];
  }
  const items: AttentionItem[] = [];
  const open = alerts ?? [];
  const routerAlerts = open.filter((a) => a.kind === 'ROUTER_OFFLINE');
  const offline = Math.max(d.network?.routersByStatus?.['OFFLINE'] ?? 0, routerAlerts.length);
  if (offline > 0) {
    const one = offline === 1 && routerAlerts.length === 1 ? routerAlerts[0] : null;
    items.push({
      key: 'routers',
      tone: 'error',
      icon: IconRouterOff,
      text: one?.subjectLabel
        ? `Router ${one.subjectLabel} is offline`
        : plural(offline, 'router') + ' offline',
      detail: 'Customers behind it cannot connect.',
      action: offline === 1 ? 'Inspect router' : 'Inspect routers',
      link: one?.subjectId ? `/network/${one.subjectId}` : '/network',
    });
  }
  const others = open.length - routerAlerts.length;
  if (others > 0) {
    items.push({
      key: 'alerts',
      tone: 'error',
      icon: IconAlertTriangle,
      text: `${plural(others, 'alert')} open`,
      detail: 'Failed router changes or SMS account problems.',
      action: 'Review alerts',
      link: '/alerts',
    });
  }
  const suspense = d.suspense?.payments ?? 0;
  if (suspense > 0) {
    items.push({
      key: 'suspense',
      tone: 'warning',
      icon: IconInbox,
      text: `${plural(suspense, 'payment')} not matched to a customer`,
      detail: `${formatMoney(d.suspense?.amount)} waiting in suspense.`,
      action: 'Open suspense',
      link: '/payments/suspense',
    });
  }
  const status = d.lines?.byDisplayStatus ?? {};
  const capped = status['CAPPED'] ?? 0;
  // Staff get the calls from capped customers too, so they count under Restricted as well
  // (decisions.md 2026-10-21).
  const restricted = (status['RESTRICTED'] ?? 0) + capped;
  const lines: LineRow[] = [
    {
      key: 'RESTRICTED',
      count: restricted,
      icon: IconLock,
      what: 'restricted',
      detail:
        capped > 0
          ? `Only the pay page works. ${capped} of them used up their data cap.`
          : 'Expired past grace; only the pay page works.',
      action: 'Review lines',
    },
    {
      key: 'CAPPED',
      count: capped,
      icon: IconDatabaseOff,
      what: 'capped',
      detail: 'Data cap used up this cycle; renewing reconnects them.',
      action: 'Review lines',
    },
    {
      key: 'THROTTLED',
      count: status['THROTTLED'] ?? 0,
      icon: IconGauge,
      what: 'throttled (fair use)',
      detail: 'Past the fair-use allowance; renewing early restores full speed.',
      action: 'Review lines',
    },
    {
      key: 'GRACE',
      count: status['GRACE'] ?? 0,
      icon: IconHourglassLow,
      what: 'in grace',
      detail: 'Expired, still online for their grace days.',
      action: 'Review accounts',
    },
    {
      key: 'EXPIRING',
      count: status['EXPIRING'] ?? 0,
      icon: IconClockExclamation,
      what: 'expiring within 3 days',
      detail: 'Reminders go out by SMS.',
      action: 'Review renewals',
    },
  ];
  for (const row of lines) {
    if (row.count > 0) {
      items.push({
        key: row.key,
        tone: 'warning',
        icon: row.icon,
        text: `${plural(row.count, 'line')} ${row.what}`,
        detail: row.detail,
        action: row.action,
        link: '/subscriptions',
        queryParams: { displayStatus: row.key },
      });
    }
  }
  // Signed up but never paid: chase or cancel before activation; never cancelled automatically
  // (docs/04, decisions.md 2026-10-21).
  const stale = d.lines?.pendingOver14Days ?? 0;
  if (stale > 0) {
    items.push({
      key: 'PENDING_STALE',
      tone: 'warning',
      icon: IconUserPause,
      text: `${plural(stale, 'line')} pending over 14 days`,
      detail: 'Never paid. Chase the first payment, or cancel the line.',
      action: 'Review pending lines',
      link: '/subscriptions',
      queryParams: { displayStatus: 'PENDING' },
    });
  }
  return items;
}

interface LineRow {
  key: string;
  count: number;
  icon: TablerIcon;
  what: string;
  detail: string;
  action: string;
}

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}
