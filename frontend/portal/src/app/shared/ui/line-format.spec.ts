import { PortalLine } from '../models';
import { allowance, allowanceText, lineSummary, speedText } from './line-format';

const line = (over: Partial<PortalLine>): PortalLine => ({
  id: 'l1',
  packageName: 'Home 10',
  downloadKbps: 10_000,
  uploadKbps: 5_000,
  expiresAt: '2026-11-20T09:00:00Z',
  ...over,
});

describe('line-format', () => {
  it('N9_explainsAFairUseThrottleApartFromAGraceThrottle', () => {
    expect(lineSummary(line({ displayStatus: 'THROTTLED', accessReason: 'FAIR_USE' }))).toContain(
      'fair-use allowance',
    );
    expect(
      lineSummary(line({ displayStatus: 'THROTTLED', accessReason: 'GRACE_THROTTLE' })),
    ).toContain('until you pay');
    expect(lineSummary(line({ displayStatus: 'CAPPED', accessReason: 'DATA_CAP' }))).toContain(
      'data is used up',
    );
    expect(lineSummary(line({ displayStatus: 'RESTRICTED' }))).toContain('Pay to get back online');
  });

  it('shows speeds in megabits', () => {
    expect(speedText(line({}))).toBe('10 Mbps down · 5 Mbps up');
    expect(speedText(line({ downloadKbps: 512, uploadKbps: 256 }))).toBe(
      '512 kbps down · 256 kbps up',
    );
  });

  it('N9_metersAgainstTheDataCapFirstThenTheFairUseAllowance', () => {
    const cap = allowance({ usedBytes: 50e9, dataCapBytes: 100e9, fairUseBytes: 40e9 });
    expect(cap?.label).toBe('Data');
    expect(cap?.share).toBe(0.5);
    expect(allowanceText(cap!)).toBe('50.0 GB of 100 GB');

    const fup = allowance({ usedBytes: 120e9, fairUseBytes: 100e9, fairUseExceeded: true });
    expect(fup?.label).toBe('Fair use');
    expect(fup?.share).toBe(1);
    expect(fup?.reached).toBe(true);

    expect(allowance({ usedBytes: 5e9 })).toBeNull();
    expect(allowance(undefined)).toBeNull();
  });
});
