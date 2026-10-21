import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { PortalCustomer, SignInResponse, TokenClaims } from '../../shared/models';
import { API_BASE } from '../api/api.config';

const AUTH = `${API_BASE}/portal-auth`;

/**
 * Customer session (ADR 0010; Q7 default: a PIN, with an SMS code to set or reset it). The access
 * token lives in memory only; the refresh token is an httpOnly cookie the browser sends to
 * `/api/v1/portal-auth/session` alone, so a reload restores the session through {@link restore}.
 * Nothing here is a security boundary: the server checks every request.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly tokenSignal = signal<string | null>(null);
  private readonly customerSignal = signal<PortalCustomer | null>(null);
  private refreshing: Promise<boolean> | null = null;

  readonly token = this.tokenSignal.asReadonly();
  readonly customer = this.customerSignal.asReadonly();
  readonly claims = computed<TokenClaims | null>(() => decodeClaims(this.tokenSignal()));
  readonly isAuthenticated = computed(() => this.tokenSignal() !== null);

  /** Account number and PIN; the provider is the account's own (its letters, T2). */
  login(tenant: string, account: string, pin: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/login`, { tenant, account, pin });
  }

  /** Sends a sign-in code by SMS; the answer is the same whether or not the phone is known. */
  async requestCode(tenant: string, phone: string): Promise<void> {
    await firstValueFrom(this.http.post(`${AUTH}/code`, { tenant, phone }));
  }

  /** The code from the SMS: a PIN to set, or first the account to set it for. */
  verifyCode(tenant: string, phone: string, code: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/code/verify`, { tenant, phone, code });
  }

  setPin(challenge: string, pin: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/pin`, { challenge, pin });
  }

  /**
   * A new access token from the refresh cookie; false when there is no valid session. Concurrent
   * callers in this tab share one call, and tabs take turns through a Web Lock: the server rotates the
   * refresh token on every use and treats a second use of the old one as theft (ADR 0010).
   */
  restore(): Promise<boolean> {
    this.refreshing ??= exclusively(() =>
      firstValueFrom(this.http.post<SignInResponse>(`${AUTH}/session/refresh`, null)),
    )
      .then(async (response) => {
        await this.accept(response);
        return this.isAuthenticated();
      })
      .catch(() => {
        this.clear();
        return false;
      })
      .finally(() => (this.refreshing = null));
    return this.refreshing;
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post(`${AUTH}/session/logout`, null));
    } catch {
      // Signed out locally either way; the refresh token expires on its own.
    } finally {
      this.clear();
    }
  }

  setToken(token: string | null): void {
    this.tokenSignal.set(token);
  }

  clear(): void {
    this.tokenSignal.set(null);
    this.customerSignal.set(null);
  }

  private async step(url: string, body: unknown): Promise<SignInResponse> {
    const response = await firstValueFrom(this.http.post<SignInResponse>(url, body));
    await this.accept(response);
    return response;
  }

  private async accept(response: SignInResponse): Promise<void> {
    if (response.status === 'SIGNED_IN' && response.accessToken) {
      this.tokenSignal.set(response.accessToken);
      this.customerSignal.set(
        await firstValueFrom(this.http.get<PortalCustomer>(`${API_BASE}/portal/customer`)),
      );
    }
  }
}

/** Runs {@code work} holding the cross-tab refresh lock where the browser has Web Locks. */
function exclusively<T>(work: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  return locks ? locks.request('corebalt-portal-session-refresh', work) : work();
}

/** Decodes the payload of a JWT without verifying it (verification is the server's job). */
export function decodeClaims(token: string | null): TokenClaims | null {
  const payload = token?.split('.')[1];
  if (!payload) {
    return null;
  }
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as TokenClaims;
  } catch {
    return null;
  }
}

/**
 * The provider of an account number: its leading letters (T2: tenant code, then six digits, so
 * `cb000001` → `CB`). Null when the text does not look like an account number.
 */
export function tenantOfAccount(account: string): string | null {
  const match = /^\s*([A-Za-z]{2,4})\d{6}(-\d+)?\s*$/.exec(account);
  return match ? match[1].toUpperCase() : null;
}
