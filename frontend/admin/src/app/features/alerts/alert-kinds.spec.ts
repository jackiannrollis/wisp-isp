import { alertKind, alertLink } from './alert-kinds';

describe('alertLink', () => {
  it('opens the router for ROUTER_OFFLINE', () => {
    expect(alertLink({ kind: 'ROUTER_OFFLINE', subjectId: 'r1' })).toBe('/network/r1');
  });

  it("opens the line's customer for COA_FAILED (N5), or the lines when the line is gone", () => {
    expect(alertLink({ kind: 'COA_FAILED', subjectId: 'l1', customerId: 'c1' })).toBe(
      '/customers/c1',
    );
    expect(alertLink({ kind: 'COA_FAILED', subjectId: 'l1' })).toBe('/subscriptions');
  });

  it('sends SMS account problems to settings', () => {
    expect(alertLink({ kind: 'SMS_CREDIT_EXHAUSTED' })).toBe('/settings');
  });
});

describe('alertKind', () => {
  it('names a kind the screen does not know yet by its code', () => {
    expect(alertKind('SOMETHING_NEW').label).toBe('SOMETHING_NEW');
  });
});
