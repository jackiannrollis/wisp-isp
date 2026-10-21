import { TestBed } from '@angular/core/testing';

import { UsageBarsComponent, lastDays } from './usage-bars.component';

describe('UsageBarsComponent', () => {
  it('lastDays counts back from today, or from a later provider day', () => {
    const today = new Date(2026, 9, 21, 12);
    expect(lastDays(3, undefined, today)).toEqual(['2026-10-19', '2026-10-20', '2026-10-21']);
    expect(lastDays(2, '2026-10-22', today)).toEqual(['2026-10-21', '2026-10-22']);
    expect(lastDays(2, '2026-10-01', today)).toEqual(['2026-10-20', '2026-10-21']);
  });

  it('N7_showsEveryDayOfTheWindowWithZerosWhereTheRollUpHasNoRow', async () => {
    const fixture = TestBed.createComponent(UsageBarsComponent);
    fixture.componentRef.setInput('days', [
      { day: lastDays(1)[0], inputOctets: 1_000_000_000, outputOctets: 4_000_000_000 },
    ]);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelectorAll('.slot')).toHaveLength(30);
    expect(page.textContent).toContain('5.0 GB in the last 30 days');
  });
});
