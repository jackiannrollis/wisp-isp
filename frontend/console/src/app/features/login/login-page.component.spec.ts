import { nextStep, safeNext } from './login-page.component';

describe('nextStep', () => {
  it('follows what the server asks for next', () => {
    expect(nextStep({ status: 'CHANGE_PASSWORD', challenge: 'c' })).toBe('CHANGE_PASSWORD');
    expect(nextStep({ status: 'TOTP_ENROLL', challenge: 'c', totpSecret: 'S' })).toBe(
      'TOTP_ENROLL',
    );
    expect(nextStep({ status: 'TOTP_REQUIRED', challenge: 'c' })).toBe('TOTP_REQUIRED');
    expect(nextStep({ status: 'SIGNED_IN', accessToken: 't' })).toBe('SIGNED_IN');
  });

  it('starts over on an answer it does not know', () => {
    expect(nextStep({ status: 'SOMETHING_NEW' })).toBe('credentials');
    expect(nextStep({})).toBe('credentials');
  });
});

describe('safeNext', () => {
  it('stays inside the console', () => {
    expect(safeNext('/tenants/42')).toBe('/tenants/42');
    expect(safeNext('https://evil.example')).toBe('/tenants');
    expect(safeNext('//evil.example/x')).toBe('/tenants');
    expect(safeNext('/login?next=/x')).toBe('/tenants');
    expect(safeNext(undefined)).toBe('/tenants');
  });
});
