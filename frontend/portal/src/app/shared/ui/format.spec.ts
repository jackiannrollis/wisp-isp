import { formatBytes, formatMoney } from './format';
import { statusLabel, statusTone } from './status-chip.component';

describe('formatting', () => {
  it('shows minor units as currency with its code (B2: display only)', () => {
    expect(formatMoney({ amount: 250000, currency: 'KES' })).toBe('KES 2,500.00');
    expect(formatMoney({ amount: -150, currency: 'KES' })).toBe('-KES 1.50');
    expect(formatMoney(null)).toBe('—');
  });

  it('shows data in decimal units', () => {
    expect(formatBytes(8_589_935_592)).toBe('8.6 GB');
    expect(formatBytes(123_456)).toBe('123 KB');
    expect(formatBytes(512)).toBe('512 B');
  });

  it('gives every status a tone and readable text', () => {
    expect(statusTone('RESTRICTED')).toBe('error');
    expect(statusTone('EXPIRING')).toBe('warning');
    expect(statusTone('ONLINE')).toBe('success');
    expect(statusTone('SOMETHING_NEW')).toBe('neutral');
    expect(statusLabel('NOT_MONITORED')).toBe('Not monitored');
  });
});
