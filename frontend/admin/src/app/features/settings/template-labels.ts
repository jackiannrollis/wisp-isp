/** What each SMS template is for, in the order staff think of them: reminders, then payment, then changes. */
export const TEMPLATE_LABELS: Record<string, { label: string; when: string }> = {
  EXPIRING_3D: { label: 'Expires in 3 days', when: 'Reminder, 3 days before expiry' },
  EXPIRING_3D_AUTO: {
    label: 'Renews from credit in 3 days',
    when: 'Reminder when the wallet already holds the price',
  },
  EXPIRING_1D: { label: 'Expires tomorrow', when: 'Reminder, 1 day before expiry' },
  EXPIRING_1D_AUTO: {
    label: 'Renews from credit tomorrow',
    when: 'Reminder when the wallet already holds the price',
  },
  DUE_TODAY: { label: 'Due today', when: 'Reminder on the expiry day' },
  DUE_TODAY_AUTO: {
    label: 'Renews from credit today',
    when: 'On the expiry day, wallet covers it',
  },
  GRACE_STARTED: { label: 'Grace period started', when: 'When the line expires with grace days' },
  RESTRICTED: { label: 'Restricted', when: 'When the line moves to the walled garden' },
  PAYMENT_RECEIVED_RENEWED: { label: 'Payment: renewed', when: 'Payment renewed an active line' },
  PAYMENT_RECEIVED_RESTORED: {
    label: 'Payment: internet restored',
    when: 'Payment renewed a restricted line',
  },
  PAYMENT_RECEIVED_ACTIVATED: {
    label: 'Payment: line activated',
    when: 'First payment on a new line',
  },
  PAYMENT_RECEIVED_MULTI: {
    label: 'Payment: several lines',
    when: 'One payment renewed several lines',
  },
  PAYMENT_RECEIVED_PARTIAL: { label: 'Payment: not enough yet', when: 'Payment below the price' },
  PAYMENT_RECEIVED_CREDIT: { label: 'Payment: kept as credit', when: 'Nothing to renew yet' },
  PAYMENT_RECEIVED_POSTPAID: {
    label: 'Payment: postpaid account',
    when: 'Payment on a postpaid account',
  },
  PAYMENT_UNMATCHED: {
    label: 'Account number not recognised',
    when: 'Reply to a payer whose reference matched no one',
  },
  PACKAGE_CHANGED_NOW: { label: 'Package changed now', when: 'Upgrade applied at once' },
  PACKAGE_CHANGED_NEXT: {
    label: 'Package changes at renewal',
    when: 'Downgrade or change scheduled',
  },
  MOVED_NOTICE: {
    label: 'We have moved',
    when: 'Sent to an imported batch when you ask: the new account number',
  },
  MOVED_NOTICE_LEGACY: {
    label: 'We have moved (old reference kept)',
    when: 'The same, for customers whose old account reference still matches',
  },
};

export function templateLabel(key: string | undefined): { label: string; when: string } {
  return TEMPLATE_LABELS[key ?? ''] ?? { label: key ?? '', when: '' };
}
