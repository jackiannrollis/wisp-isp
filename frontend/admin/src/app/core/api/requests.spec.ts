import { HttpErrorResponse } from '@angular/common/http';

import { errorMessage, shillingsToMinor } from './requests';

describe('shillingsToMinor', () => {
  it('turns typed shillings into integer cents without floating point (B2)', () => {
    expect(shillingsToMinor('2500')).toBe(250_000);
    expect(shillingsToMinor('2,500.5')).toBe(250_050);
    expect(shillingsToMinor(' 0.07 ')).toBe(7);
    expect(shillingsToMinor('19.99')).toBe(1_999);
  });

  it('refuses what is not an amount, and negatives unless allowed', () => {
    expect(shillingsToMinor('')).toBeNull();
    expect(shillingsToMinor('12.345')).toBeNull();
    expect(shillingsToMinor('1e3')).toBeNull();
    expect(shillingsToMinor('-500')).toBeNull();
    expect(shillingsToMinor('-500', true)).toBe(-50_000);
  });
});

describe('errorMessage', () => {
  it('shows the problem detail, a field error first, never internals', () => {
    expect(
      errorMessage(
        new HttpErrorResponse({ status: 409, error: { detail: 'Customer CB000001 is archived.' } }),
      ),
    ).toBe('Customer CB000001 is archived.');
    expect(
      errorMessage(
        new HttpErrorResponse({
          status: 400,
          error: {
            detail: 'Invalid request',
            errors: [{ field: 'phone', message: 'not a valid phone number' }],
          },
        }),
      ),
    ).toBe('phone: not a valid phone number');
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toContain('cannot be reached');
    expect(errorMessage(new Error('boom'))).toBe('That did not work. Try again.');
  });
});
