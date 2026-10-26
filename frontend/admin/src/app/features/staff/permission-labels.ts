/** What each permission code lets a staff member do, in the order the role editor groups them. */
export interface PermissionLabel {
  label: string;
  description: string;
  group: 'Customers and lines' | 'Money' | 'Network' | 'Administration';
}

export const PERMISSION_LABELS: Record<string, PermissionLabel> = {
  'customers.write': {
    label: 'Edit customers',
    description: 'Add, edit and archive customers.',
    group: 'Customers and lines',
  },
  'subscriptions.write': {
    label: 'Manage lines',
    description:
      'Open, pause, suspend and close lines, change packages, new PPPoE passwords. Importing customers needs this and Edit customers.',
    group: 'Customers and lines',
  },
  'subscriptions.import_active': {
    label: 'Cutover import',
    description:
      'Import lines already active with the expiry customers paid for in the old system, while Corebalt has opened the cutover window; correct kept PPPoE usernames. Owner only.',
    group: 'Customers and lines',
  },
  'packages.write': {
    label: 'Edit packages',
    description: 'Create and change packages and prices.',
    group: 'Customers and lines',
  },
  'finance.read': {
    label: 'See money',
    description: 'Revenue figures, wallet balances, statements, receipts and payments.',
    group: 'Money',
  },
  'payments.record_manual': {
    label: 'Record payments',
    description: 'Record cash, bank and M-Pesa payments by hand.',
    group: 'Money',
  },
  'payments.stk': {
    label: 'Send M-Pesa requests',
    description: "Send an STK Push to a customer's phone.",
    group: 'Money',
  },
  'payments.assign': {
    label: 'Assign suspense',
    description: 'Assign unmatched payments to a customer.',
    group: 'Money',
  },
  'payments.reconcile': {
    label: 'Reconcile',
    description: 'Upload M-Pesa statements and ask M-Pesa about missing payments.',
    group: 'Money',
  },
  'billing.adjust': {
    label: 'Adjust wallets',
    description: 'Post wallet adjustments with a reason, for example free days.',
    group: 'Money',
  },
  'network.write': {
    label: 'Manage routers',
    description: 'Register routers, set monitoring logins and issue new RADIUS secrets.',
    group: 'Network',
  },
  'settings.notifications': {
    label: 'SMS templates',
    description: 'Edit message wording and the sending window; clear refused numbers.',
    group: 'Administration',
  },
  'settings.secrets': {
    label: 'Account keys',
    description: "Set M-Pesa and Africa's Talking credentials.",
    group: 'Administration',
  },
  'settings.write': {
    label: 'Account settings',
    description: 'Grace policy, partial payments, pause limit, branding, SMS sender. Owner only.',
    group: 'Administration',
  },
  'staff.manage': {
    label: 'Manage staff',
    description: 'Invite, disable and re-role staff; edit roles.',
    group: 'Administration',
  },
  'audit.read': {
    label: 'Read the audit log',
    description: 'See who changed what, and when.',
    group: 'Administration',
  },
};

export const PERMISSION_GROUPS: PermissionLabel['group'][] = [
  'Customers and lines',
  'Money',
  'Network',
  'Administration',
];

export function permissionLabel(code: string): PermissionLabel {
  return PERMISSION_LABELS[code] ?? { label: code, description: '', group: 'Administration' };
}
