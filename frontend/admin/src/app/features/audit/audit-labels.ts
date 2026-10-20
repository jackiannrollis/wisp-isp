import { formatDateTime, formatMoney } from '../../shared/ui/format';
import { AuditRow } from './data/audit.service';

/** The action codes the API writes (A1), in staff language. Unknown codes are humanised. */
const ACTIONS: Record<string, string> = {
  'identity.signed_in': 'Signed in',
  'identity.locked_out': 'Locked out after failed sign-ins',
  'identity.password_changed': 'Changed their password',
  'identity.password_reset': 'Password reset',
  'identity.pin_set': 'Portal PIN set',
  'identity.totp_enrolled': 'Set up 2FA',
  'identity.staff_invited': 'Staff invited',
  'identity.invitation_regenerated': 'Invitation renewed',
  'identity.temporary_password_sms': 'Temporary password sent by SMS',
  'identity.staff_disabled': 'Staff disabled',
  'identity.staff_enabled': 'Staff enabled',
  'identity.staff_role_changed': 'Staff role changed',
  'identity.role_created': 'Role created',
  'identity.role_changed': 'Role changed',
  'identity.impersonation_started': 'Corebalt support started acting as staff',
  'identity.impersonation_ended': 'Corebalt support stopped acting as staff',
  'identity.impersonation_expired': 'Corebalt support session as staff expired',
  'identity.tenant_created': 'Account created',
  'subscriptions.line_opened': 'Line opened',
  'subscriptions.renewed': 'Line renewed',
  'subscriptions.package_changed': 'Package changed',
  'subscriptions.paused': 'Line paused',
  'subscriptions.resumed': 'Line resumed',
  'subscriptions.suspended': 'Line suspended',
  'subscriptions.suspension_lifted': 'Suspension lifted',
  'subscriptions.terminated': 'Line closed',
  'billing.wallet_adjusted': 'Wallet adjusted',
  'payments.manual_recorded': 'Payment recorded by hand',
  'payments.assigned': 'Payment assigned from suspense',
  'payments.reconciled': 'Statement reconciled',
  'payments.mpesa_configured': 'M-Pesa settings changed',
  'payments.mpesa_urls_registered': 'M-Pesa URLs registered',
  'network.pppoe_password_issued': 'PPPoE password issued',
  'network.pppoe_password_regenerated': 'PPPoE password replaced',
  'network.pppoe_password_revoked': 'PPPoE password revoked',
  'network.radius_secret_issued': 'Router RADIUS secret issued',
  'network.radius_secret_regenerated': 'Router RADIUS secret replaced',
  'network.router_rest_credential_set': 'Router monitoring login set',
  'network.router_rest_credential_removed': 'Router monitoring login removed',
  'notifications.sms_configured': 'SMS settings changed',
  'tenancy.secret_replaced': 'Account key replaced',
};

const TARGETS: Record<string, string> = {
  customer: 'Customer',
  subscription: 'Line',
  staff_user: 'Staff',
  role: 'Role',
  router: 'Router',
  payment: 'Payment',
  reconciliation_run: 'Reconciliation',
  tenant: 'Account',
  tenant_secret: 'Account key',
};

/** The target as a noun in a sentence: "everything about this staff member". */
export function targetNoun(targetType: string | undefined): string {
  if (targetType === 'staff_user') {
    return 'staff member';
  }
  return targetLabel(targetType).toLowerCase() || 'item';
}

/** The area filter: the API matches the action by prefix. */
export const AUDIT_AREAS = [
  { value: 'subscriptions.', label: 'Lines' },
  { value: 'payments.', label: 'Payments' },
  { value: 'billing.', label: 'Wallets' },
  { value: 'network.', label: 'Network' },
  { value: 'identity.', label: 'Staff and sign-in' },
  { value: 'tenancy.', label: 'Account keys' },
  { value: 'notifications.', label: 'SMS' },
];

export function actionLabel(action: string | undefined): string {
  if (!action) {
    return '';
  }
  return ACTIONS[action] ?? humanise(action.slice(action.indexOf('.') + 1));
}

export function targetLabel(targetType: string | undefined): string {
  return targetType ? (TARGETS[targetType] ?? humanise(targetType)) : '';
}

/** Who did it: a staff member by name, Corebalt support during an impersonation, a customer or the platform. */
export function actorLabel(row: AuditRow): string {
  switch (row.actorType) {
    case 'STAFF':
      return row.actorName ?? 'Staff member';
    case 'PLATFORM':
      return row.onBehalfOf ? 'Corebalt support, acting as staff' : 'Corebalt support';
    case 'CUSTOMER':
      return 'The customer';
    default:
      return 'System';
  }
}

/** Where the target can be opened, when the screen has a page for it. */
export function targetLink(row: AuditRow): string | null {
  switch (row.targetType) {
    case 'customer':
      return row.targetId ? `/customers/${row.targetId}` : null;
    case 'router':
      return row.targetId ? `/network/${row.targetId}` : null;
    case 'staff_user':
    case 'role':
      return '/staff';
    case 'reconciliation_run':
      return '/payments/reconciliation';
    default:
      return null;
  }
}

export interface FieldChange {
  field: string;
  before: string;
  after: string;
  changed: boolean;
  /** An ID field: kept in the detail table, left out of the one-line summary. */
  id: boolean;
}

/** Before and after side by side, one row per field; unchanged fields stay, marked as such. */
export function diff(before: string | undefined, after: string | undefined): FieldChange[] {
  const a = parse(before);
  const b = parse(after);
  const fields = [...new Set([...Object.keys(a), ...Object.keys(b)])];
  return fields.map((field) => {
    const money = /Minor$/.test(field);
    const was = show(a[field], money);
    const now = show(b[field], money);
    return {
      field: humanise(money ? field.replace(/Minor$/, '') : field),
      before: was,
      after: now,
      changed: was !== now,
      id: /(Id|_id)$/.test(field),
    };
  });
}

function parse(json: string | undefined): Record<string, unknown> {
  if (!json) {
    return {};
  }
  try {
    const value: unknown = JSON.parse(json);
    return value && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : { value };
  } catch {
    return { value: json };
  }
}

/** ISO instants read as local dates; `…Minor` fields are KES minor units (B2) and read as money. */
function show(value: unknown, money = false): string {
  if (value === undefined || value === null) {
    return '';
  }
  if (Array.isArray(value)) {
    return value.map((v) => show(v)).join(', ');
  }
  if (money && typeof value === 'number') {
    return formatMoney({ amount: value, currency: 'KES' });
  }
  if (typeof value === 'string' && ISO_INSTANT.test(value)) {
    return formatDateTime(value);
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/;

/** `suspension_lifted` and `balanceAfterMinor` read as "Suspension lifted" and "Balance after minor". */
export function humanise(code: string): string {
  const words = code
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_.]+/g, ' ')
    .trim()
    .toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
