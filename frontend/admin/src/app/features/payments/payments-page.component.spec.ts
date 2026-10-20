import { withPeriod } from './payments-page.component';

describe('withPeriod', () => {
  const now = new Date(2026, 9, 20, 15, 30); // 20 Oct 2026, 15:30 local
  const base = { page: 0, size: 50, filters: {} as Record<string, string> };

  it('leaves a query without a period alone', () => {
    expect(withPeriod({ ...base, filters: { status: 'MATCHED' } }, now)).toEqual({
      ...base,
      filters: { status: 'MATCHED' },
    });
  });

  it('turns a period into receivedFrom at local midnight and drops the period key', () => {
    const today = withPeriod({ ...base, filters: { period: 'today', channel: 'CASH' } }, now);
    expect(today.filters).toEqual({
      channel: 'CASH',
      receivedFrom: new Date(2026, 9, 20).toISOString(),
    });
    expect(withPeriod({ ...base, filters: { period: 'week' } }, now).filters['receivedFrom']).toBe(
      new Date(2026, 9, 14).toISOString(),
    );
    expect(withPeriod({ ...base, filters: { period: 'month' } }, now).filters['receivedFrom']).toBe(
      new Date(2026, 9, 1).toISOString(),
    );
  });
});
