import { Line } from './data/lines.service';
import { accessText, usageText } from './line-format';

describe('line format', () => {
  it('says why access is limited (S1)', () => {
    expect(accessText({ accessProfile: 'THROTTLED', accessReason: 'FAIR_USE' } as Line)).toBe(
      'Throttled · fair use',
    );
    expect(accessText({ accessProfile: 'FULL' } as Line)).toBe('Full');
  });

  it('shows usage against the allowance, and nothing while the line is not metered (N9)', () => {
    const used = (usage: Line['usage']) => usageText({ usage } as Line);
    expect(
      used({
        cycleStartedAt: '2026-10-01T00:00:00Z',
        usedBytes: 12_500_000_000,
        fairUseBytes: 100_000_000_000,
      }),
    ).toBe('12.5 GB of 100 GB');
    expect(used({ cycleStartedAt: '2026-10-01T00:00:00Z', usedBytes: 3_000_000_000 })).toBe(
      '3.0 GB',
    );
    expect(used({ usedBytes: 0 })).toBe('—');
  });
});
