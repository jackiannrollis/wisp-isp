import {
  IconAlertTriangle,
  IconClockExclamation,
  IconHourglassLow,
  IconInbox,
  IconLock,
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
 * then lines that lost or are about to lose service. Only rows with something to do appear; the
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
  const lines: [string, TablerIcon, string, string, string][] = [
    [
      'RESTRICTED',
      IconLock,
      'restricted',
      'Expired past grace; only the pay page works.',
      'Review lines',
    ],
    [
      'GRACE',
      IconHourglassLow,
      'in grace',
      'Expired, still online for their grace days.',
      'Review accounts',
    ],
    [
      'EXPIRING',
      IconClockExclamation,
      'expiring within 3 days',
      'Reminders go out by SMS.',
      'Review renewals',
    ],
  ];
  for (const [key, icon, what, detail, action] of lines) {
    const count = status[key] ?? 0;
    if (count > 0) {
      items.push({
        key,
        tone: 'warning',
        icon,
        text: `${plural(count, 'line')} ${what}`,
        detail,
        action,
        link: '/subscriptions',
        queryParams: { displayStatus: key },
      });
    }
  }
  return items;
}

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}
