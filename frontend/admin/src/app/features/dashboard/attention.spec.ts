import { attentionItems } from './attention';
import type { Dashboard } from './data/dashboard.service';

const base: Dashboard = {
  lines: { total: 10, byState: {}, byDisplayStatus: { ACTIVE: 10 } },
  network: { routersByStatus: { ONLINE: 2, OFFLINE: 0 }, activeSessions: 5 },
};

describe('attentionItems', () => {
  it('is empty when nothing needs doing', () => {
    expect(attentionItems(base, [])).toEqual([]);
    expect(attentionItems(undefined, [])).toEqual([]);
  });

  it('leads one offline router to its own page, by name', () => {
    const items = attentionItems(
      { ...base, network: { routersByStatus: { ONLINE: 1, OFFLINE: 1 } } },
      [{ kind: 'ROUTER_OFFLINE', subjectId: 'r1', subjectLabel: 'ruiru-core' }],
    );
    expect(items.map((i) => [i.key, i.text, i.link])).toEqual([
      ['routers', 'Router ruiru-core is offline', '/network/r1'],
    ]);
  });

  it('orders routers and alerts first, then suspense, then lines, and pluralises', () => {
    const items = attentionItems(
      {
        ...base,
        network: { routersByStatus: { OFFLINE: 2 } },
        suspense: { payments: 1, amount: { amount: 150_000, currency: 'KES' } },
        lines: { byDisplayStatus: { EXPIRING: 3, GRACE: 1, RESTRICTED: 2 } },
      },
      [{ kind: 'COA_FAILED' }, { kind: 'SMS_CREDIT_EXHAUSTED' }],
    );
    expect(items.map((i) => i.key)).toEqual([
      'routers',
      'alerts',
      'suspense',
      'RESTRICTED',
      'GRACE',
      'EXPIRING',
    ]);
    expect(items[0].text).toBe('2 routers offline');
    expect(items[0].link).toBe('/network');
    expect(items[1].text).toBe('2 alerts open');
    expect(items[2].text).toBe('1 payment not matched to a customer');
    expect(items[5]).toMatchObject({
      text: '3 lines expiring within 3 days',
      link: '/subscriptions',
      queryParams: { displayStatus: 'EXPIRING' },
    });
  });

  it('lists throttled and capped lines, and counts capped ones under restricted too', () => {
    const items = attentionItems(
      { ...base, lines: { byDisplayStatus: { RESTRICTED: 1, CAPPED: 2, THROTTLED: 4 } } },
      [],
    );
    expect(items.map((i) => [i.key, i.text])).toEqual([
      ['RESTRICTED', '3 lines restricted'],
      ['CAPPED', '2 lines capped'],
      ['THROTTLED', '4 lines throttled (fair use)'],
    ]);
    expect(items[0].detail).toContain('2 of them used up their data cap');
    expect(items[2].queryParams).toEqual({ displayStatus: 'THROTTLED' });
  });

  it('S9_listsLinesPendingForMoreThan14DaysLast', () => {
    const items = attentionItems(
      { ...base, lines: { byDisplayStatus: { EXPIRING: 1 }, pendingOver14Days: 2 } },
      [],
    );
    expect(items.map((i) => [i.key, i.text])).toEqual([
      ['EXPIRING', '1 line expiring within 3 days'],
      ['PENDING_STALE', '2 lines pending over 14 days'],
    ]);
    expect(items[1].queryParams).toEqual({ displayStatus: 'PENDING' });
  });
});
