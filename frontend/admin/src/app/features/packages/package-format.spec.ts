import {
  bytesToGb,
  formatPeriod,
  gbToBytes,
  formatSpeed,
  formatSpeeds,
  kbpsToMbps,
  mbpsToKbps,
  splitPeriod,
} from './package-format';

describe('package formatting', () => {
  it('reads speeds in Mbit/s, small ones in kbit/s (N4 stores kbit/s)', () => {
    expect(formatSpeed(10_000)).toBe('10 Mbit/s');
    expect(formatSpeed(2_500)).toBe('2.5 Mbit/s');
    expect(formatSpeed(512)).toBe('512 kbit/s');
    expect(formatSpeeds(10_000, 5_000)).toBe('10 / 5 Mbit/s');
    expect(formatSpeeds(2_000, 512)).toBe('2 Mbit/s / 512 kbit/s');
  });

  it('reads periods in the largest whole unit', () => {
    expect(formatPeriod(43_200)).toBe('30 days');
    expect(formatPeriod(1_440)).toBe('1 day');
    expect(formatPeriod(720)).toBe('12 hours');
    expect(formatPeriod(90)).toBe('90 minutes');
  });

  it('converts typed Mbit/s to whole kbit/s and back', () => {
    expect(mbpsToKbps('10')).toBe(10_000);
    expect(mbpsToKbps(' 2.5 ')).toBe(2_500);
    expect(mbpsToKbps('0.512')).toBe(512);
    expect(mbpsToKbps('0')).toBeNull();
    expect(mbpsToKbps('1.2345')).toBeNull();
    expect(mbpsToKbps('fast')).toBeNull();
    expect(kbpsToMbps(2_500)).toBe('2.5');
  });

  it('splits a period into days when it divides, else hours', () => {
    expect(splitPeriod(43_200)).toEqual({ amount: 30, unit: 'days' });
    expect(splitPeriod(720)).toEqual({ amount: 12, unit: 'hours' });
  });
});

describe('allowances', () => {
  it('converts typed decimal gigabytes to bytes and back (N9)', () => {
    expect(gbToBytes('100')).toBe(100_000_000_000);
    expect(gbToBytes('1.5')).toBe(1_500_000_000);
    expect(gbToBytes('')).toBeNull();
    expect(gbToBytes('0')).toBeNull();
    expect(gbToBytes('lots')).toBeNull();
    expect(bytesToGb(1_500_000_000)).toBe('1.5');
    expect(bytesToGb(null)).toBe('');
  });
});
