import { resultCsv } from './import-page.component';

describe('resultCsv', () => {
  it('writes one line per imported row with the one-time password and the activation', () => {
    const csv = resultCsv([
      {
        row: 1,
        customerId: 'c1',
        number: 'CB000001',
        name: 'Wanjiku, "Shiku" Kamau',
        subscriptionId: 's1',
        pppoeUsername: 'CB000001',
        pppoePassword: 'one-time-pw',
        credentialKept: false,
        activatedUntil: '2026-11-15T09:00:00Z',
      },
      {
        row: 2,
        customerId: 'c2',
        number: 'CB000002',
        name: 'Kept',
        subscriptionId: 's2',
        pppoeUsername: 'old.user@isp',
        credentialKept: true,
      },
      { row: 3, customerId: 'c3', number: 'CB000003', name: 'No line', credentialKept: false },
    ]);
    expect(csv.split('\r\n')).toEqual([
      'row,number,name,pppoe_username,pppoe_password,credential_kept,activated_until',
      '1,CB000001,"Wanjiku, ""Shiku"" Kamau",CB000001,one-time-pw,no,2026-11-15T09:00:00Z',
      '2,CB000002,Kept,old.user@isp,,yes,',
      '3,CB000003,No line,,,no,',
      '',
    ]);
  });
});
