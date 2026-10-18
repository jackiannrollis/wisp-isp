export interface NavItem {
  path: string;
  label: string;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { path: '/dashboard', label: 'Dashboard' },
  { path: '/customers', label: 'Customers' },
  { path: '/subscriptions', label: 'Subscriptions' },
  { path: '/packages', label: 'Packages' },
  { path: '/billing', label: 'Billing' },
  { path: '/payments', label: 'Payments' },
  { path: '/network', label: 'Network' },
  { path: '/settings', label: 'Settings' },
];
