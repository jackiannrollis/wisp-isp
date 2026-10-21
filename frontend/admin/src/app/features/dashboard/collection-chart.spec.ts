import { collectionChart, collectionSummary } from './dashboard-page.component';

const kes = (amount: number) => ({ amount, currency: 'KES' });

describe('collection trend', () => {
  const days = [
    { day: '2026-10-01', payments: 2, amount: kes(500_000) },
    { day: '2026-10-02', payments: 0, amount: kes(0) },
    { day: '2026-10-03', payments: 5, amount: kes(1_250_000) },
  ];

  it('describes the period in words for screen readers', () => {
    const summary = collectionSummary(days);
    expect(summary).toMatch(
      /^Collected KES\s?17,500\.00 over 3 days, highest on 3 Oct with KES\s?12,500\.00\.$/,
    );
    expect(collectionSummary([])).toBe('No days in this period yet.');
    expect(collectionSummary([{ day: '2026-10-01', payments: 0, amount: kes(0) }])).toMatch(
      /over 1 day\.$/,
    );
  });

  it('draws one bar per day, zero days included, in shillings', () => {
    const option = collectionChart(days) as {
      xAxis: { data: string[] };
      series: { data: number[] }[];
    };
    expect(option.xAxis.data).toEqual(['1 Oct', '2 Oct', '3 Oct']);
    expect(option.series[0].data).toEqual([5_000, 0, 12_500]);
  });
});
