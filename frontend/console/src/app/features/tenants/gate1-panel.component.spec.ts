import { gate1View } from './gate1-panel.component';

describe('gate1View', () => {
  it('fills what the API leaves out and reads the criteria (ADR 0018)', () => {
    const view = gate1View({
      matching: { automatic: 49, byStaff: 1, unmatched: 0, automaticPercent: 98 },
      restores: { total: 3, completed: 2, pending: 0, missed: 1, slowerThan60s: 0, maxSeconds: 75 },
      restoresByKind: [
        { kind: 'CUT_OFF', total: 2, missed: 1, maxSeconds: 75 },
        { kind: 'GRACE_THROTTLE', total: 0 },
        { kind: 'FAIR_USE', total: 1, p95Seconds: 4 },
      ],
      reconnects: { staffDisconnects: 1, paidOfflineReports: 2, missedRestores: 1 },
      targets: { matchingAbove98: false, noRestoreSlowerThan60s: false },
    });
    expect(view.matching.percent).toBe('98.0%');
    expect(view.restores.max).toBe('1 min 15 s');
    expect(view.rows.map((r) => `${r.kind}:${r.total}`)).toEqual([
      'All:3',
      'Cut off:2',
      'Grace throttle:0',
      'Fair use:1',
    ]);
    expect(view.rows[3].p95).toBe('4 s');
    expect(view.reconnects).toEqual({
      staffDisconnects: 1,
      paidOffline: 2,
      missed: 1,
      coaFailed: 0,
      routerOffline: 0,
    });
    expect(view.results).toEqual({
      matching: 'NOT_MET',
      restores: 'NOT_MET',
      reconnects: 'NO_DATA',
    });
  });

  it('shows zeros and no data for a tenant with nothing measured', () => {
    const view = gate1View({});
    expect(view.matching).toEqual({ automatic: 0, byStaff: 0, unmatched: 0, percent: '—' });
    expect(view.rows).toHaveLength(1);
    expect(view.results.restores).toBe('NO_DATA');
  });
});
