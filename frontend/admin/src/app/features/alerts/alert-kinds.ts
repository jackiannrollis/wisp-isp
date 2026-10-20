import {
  IconMessageOff,
  IconPlugConnectedX,
  IconRouterOff,
  type TablerIcon,
} from '@tabler/icons-angular';

import { Alert } from './data/alerts.service';

export interface AlertKind {
  label: string;
  /** What staff should do about it. */
  action: string;
  icon: TablerIcon;
}

/** The alert kinds the API raises (notifications_alert_kind_ck), in staff language. */
export const ALERT_KINDS: Record<string, AlertKind> = {
  ROUTER_OFFLINE: {
    label: 'Router offline',
    action: 'Check power and the WireGuard tunnel. Customers behind it cannot connect.',
    icon: IconRouterOff,
  },
  COA_FAILED: {
    label: 'Router did not apply a change',
    action:
      'The session takes the new speed or restriction at its next login, within 24 hours. Disconnect it from the router page to apply it now.',
    icon: IconPlugConnectedX,
  },
  SMS_CREDIT_EXHAUSTED: {
    label: 'SMS credit used up',
    action: "Top up the Africa's Talking account. Waiting messages are retried every hour.",
    icon: IconMessageOff,
  },
  SENDER_ID_INVALID: {
    label: 'Sender ID refused',
    action: "Check the sender ID in SMS settings against your Africa's Talking account.",
    icon: IconMessageOff,
  },
  SMS_AUTH_FAILED: {
    label: 'SMS API key refused',
    action: "Enter the current Africa's Talking API key in SMS settings.",
    icon: IconMessageOff,
  },
  SMS_DEAD_LETTERED: {
    label: 'SMS could not be sent',
    action: "A message failed after every retry. Check the Africa's Talking status page.",
    icon: IconMessageOff,
  },
};

export function alertKind(kind: string | undefined): AlertKind {
  return ALERT_KINDS[kind ?? ''] ?? { label: kind ?? 'Alert', action: '', icon: IconMessageOff };
}

/** Where the alert's subject lives: the router, the line's customer, or SMS settings. */
export function alertLink(alert: Alert): string {
  switch (alert.kind) {
    case 'ROUTER_OFFLINE':
      return alert.subjectId ? `/network/${alert.subjectId}` : '/network';
    case 'COA_FAILED':
      return alert.customerId ? `/customers/${alert.customerId}` : '/subscriptions';
    default:
      return '/settings';
  }
}
