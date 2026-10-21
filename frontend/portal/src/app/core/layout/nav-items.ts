import { IconCash, IconReceipt, IconWifi } from '@tabler/icons-angular';

export interface NavItem {
  path: string;
  label: string;
  icon: typeof IconWifi;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { path: '/status', label: 'My internet', icon: IconWifi },
  { path: '/pay', label: 'Pay', icon: IconCash },
  { path: '/history', label: 'History', icon: IconReceipt },
];
