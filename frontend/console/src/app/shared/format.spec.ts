import { criterion, cutoverUntil, formatPercent, formatSeconds, statusLabel } from './format';

describe('cutoverUntil', () => {
  const now = new Date(2026, 9, 26, 12, 0);

  it('accepts an end within thirty days', () => {
    const result = cutoverUntil('2026-11-02T09:30', now);
    expect(result.problem).toBeUndefined();
    expect(result.until).toBe(new Date(2026, 10, 2, 9, 30).toISOString());
  });

  it('refuses a past end, one beyond thirty days, and nothing', () => {
    expect(cutoverUntil('2026-10-26T11:59', now).problem).toContain('future');
    expect(cutoverUntil('2026-11-26T12:01', now).problem).toContain('30 days');
    expect(cutoverUntil('', now).problem).toContain('Choose');
  });
});

describe('Gate 1 formats', () => {
  it('reads a criterion as met, not met or nothing to judge', () => {
    expect(criterion(true)).toBe('MET');
    expect(criterion(false)).toBe('NOT_MET');
    expect(criterion(null)).toBe('NO_DATA');
    expect(statusLabel(criterion(false))).toBe('Not met');
  });

  it('writes seconds and percentages, a dash for nothing measured', () => {
    expect(formatSeconds(45)).toBe('45 s');
    expect(formatSeconds(120)).toBe('2 min');
    expect(formatSeconds(125)).toBe('2 min 5 s');
    expect(formatSeconds(null)).toBe('—');
    expect(formatPercent(99.25)).toBe('99.3%');
    expect(formatPercent(100)).toBe('100.0%');
    expect(formatPercent(undefined)).toBe('—');
  });
});

describe('statusLabel', () => {
  it('reads like words', () => {
    expect(statusLabel('TRIAL')).toBe('Trial');
    expect(statusLabel('NOT_MONITORED')).toBe('Not monitored');
    expect(statusLabel(undefined)).toBe('—');
  });
});
