export interface NavItem {
  path: string;
  label: string;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { path: '/status', label: 'My status' },
  { path: '/pay', label: 'Pay' },
  { path: '/history', label: 'Payment history' },
];
