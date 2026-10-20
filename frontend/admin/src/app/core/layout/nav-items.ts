import {
  IconAlertTriangle,
  IconCash,
  IconHistory,
  IconInbox,
  IconLayoutDashboard,
  IconPackage,
  IconPlug,
  IconRouter,
  IconScale,
  IconSettings,
  IconUsers,
  IconUsersGroup,
  type TablerIcon,
} from '@tabler/icons-angular';

export interface NavItem {
  path: string;
  label: string;
  icon: TablerIcon;
  /** Shown when the user holds any of these codes; absent means every staff member. */
  anyOf?: readonly string[];
  /** Active only on this exact path, for an item whose path prefixes another item's. */
  exact?: boolean;
  /** A count the shell shows beside the label while it is above zero. */
  badge?: 'openAlerts';
}

export interface NavGroup {
  label: string;
  items: readonly NavItem[];
}

/**
 * The v1 navigation (ADR 0002 amendment): only screens that exist in v1. Items a user cannot use are
 * hidden, and a group with nothing left is hidden too. Hiding is a convenience; the server refuses
 * anything the user's permissions do not allow.
 */
export const NAV_GROUPS: readonly NavGroup[] = [
  {
    label: 'Overview',
    items: [{ path: '/dashboard', label: 'Dashboard', icon: IconLayoutDashboard }],
  },
  {
    label: 'Subscribers',
    items: [
      { path: '/customers', label: 'Customers', icon: IconUsers },
      { path: '/subscriptions', label: 'Lines', icon: IconPlug },
      { path: '/packages', label: 'Packages', icon: IconPackage },
    ],
  },
  {
    label: 'Finance',
    items: [
      {
        path: '/payments',
        label: 'Payments',
        icon: IconCash,
        anyOf: ['finance.read'],
        exact: true,
      },
      {
        path: '/payments/suspense',
        label: 'Suspense',
        icon: IconInbox,
        anyOf: ['finance.read', 'payments.assign'],
      },
      {
        path: '/payments/reconciliation',
        label: 'Reconciliation',
        icon: IconScale,
        anyOf: ['payments.reconcile'],
      },
    ],
  },
  {
    label: 'Network',
    items: [
      { path: '/network', label: 'Routers', icon: IconRouter },
      { path: '/alerts', label: 'Alerts', icon: IconAlertTriangle, badge: 'openAlerts' },
    ],
  },
  {
    label: 'Administration',
    items: [
      { path: '/staff', label: 'Staff and roles', icon: IconUsersGroup, anyOf: ['staff.manage'] },
      { path: '/audit-log', label: 'Audit log', icon: IconHistory, anyOf: ['audit.read'] },
      {
        path: '/settings',
        label: 'Settings',
        icon: IconSettings,
        anyOf: ['settings.secrets', 'settings.notifications'],
      },
    ],
  },
];

/** The groups and items visible with these permission codes. */
export function visibleNav(permissions: ReadonlySet<string>): NavGroup[] {
  return NAV_GROUPS.map((group) => ({
    label: group.label,
    items: group.items.filter(
      (item) => !item.anyOf || item.anyOf.some((code) => permissions.has(code)),
    ),
  })).filter((group) => group.items.length > 0);
}
