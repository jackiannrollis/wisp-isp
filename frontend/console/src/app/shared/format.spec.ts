import { cutoverUntil, statusLabel } from './format';

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

describe('statusLabel', () => {
  it('reads like words', () => {
    expect(statusLabel('TRIAL')).toBe('Trial');
    expect(statusLabel('NOT_MONITORED')).toBe('Not monitored');
    expect(statusLabel(undefined)).toBe('—');
  });
});
