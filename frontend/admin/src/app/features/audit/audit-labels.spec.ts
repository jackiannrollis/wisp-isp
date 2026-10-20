import { formatDateTime, formatMoney } from '../../shared/ui/format';
import { actionLabel, actorLabel, diff, humanise } from './audit-labels';
import { withFrom } from './audit-page.component';

describe('audit labels (A1)', () => {
  it('names known actions and humanises new ones', () => {
    expect(actionLabel('subscriptions.suspension_lifted')).toBe('Suspension lifted');
    expect(actionLabel('customers.something_new')).toBe('Something new');
    expect(humanise('balanceAfterMinor')).toBe('Balance after minor');
  });

  it('shows Corebalt support during an impersonation, never the staff name', () => {
    expect(
      actorLabel({ actorType: 'PLATFORM', onBehalfOf: 's1', actorName: 'Corebalt support' }),
    ).toBe('Corebalt support, acting as staff');
    expect(actorLabel({ actorType: 'STAFF', actorName: 'Wanjiru' })).toBe('Wanjiru');
    expect(actorLabel({ actorType: 'SYSTEM' })).toBe('System');
  });

  it('diffs before and after field by field', () => {
    expect(
      diff('{"status":"ACTIVE","role":"Support"}', '{"status":"DISABLED","role":"Support"}'),
    ).toEqual([
      { field: 'Status', before: 'ACTIVE', after: 'DISABLED', changed: true, id: false },
      { field: 'Role', before: 'Support', after: 'Support', changed: false, id: false },
    ]);
    expect(diff(undefined, '{"amountMinor":5000,"customerId":"c1"}')).toEqual([
      {
        field: 'Amount',
        before: '',
        after: formatMoney({ amount: 5000, currency: 'KES' }),
        changed: true,
        id: false,
      },
      { field: 'Customer id', before: '', after: 'c1', changed: true, id: true },
    ]);
    expect(diff(undefined, '{"expiresAt":"2026-11-19T00:16:52.586072Z"}')[0].after).toBe(
      formatDateTime('2026-11-19T00:16:52.586072Z'),
    );
    expect(diff(undefined, undefined)).toEqual([]);
  });
});

describe('withFrom', () => {
  const now = new Date(2026, 9, 20, 15, 30);
  const base = { page: 0, size: 50, filters: {} as Record<string, string> };

  it('turns the period into a from bound at local midnight', () => {
    expect(withFrom({ ...base, filters: { period: 'today' } }, now).filters).toEqual({
      from: new Date(2026, 9, 20).toISOString(),
    });
    expect(
      withFrom({ ...base, filters: { period: 'month', action: 'payments.' } }, now).filters,
    ).toEqual({
      action: 'payments.',
      from: new Date(2026, 8, 21).toISOString(),
    });
  });
});
