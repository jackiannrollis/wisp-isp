/** Claims the frontend reads from the access token. The server remains the authority. */
export interface TokenClaims {
  sub?: string;
  tenant_id?: string;
  permissions?: string[];
  exp?: number;
  /** RFC 8693 actor: the Corebalt platform admin behind an impersonation (ADR 0017). */
  act?: { sub?: string };
  /** The impersonation an impersonation token belongs to. */
  imp?: string;
}

/** What `POST /api/v1/auth/impersonation` answers for a hand-off code (ADR 0017). */
export interface ImpersonationExchange {
  accessToken: string;
  expiresAt: string;
  impersonationId: string;
  staffName: string;
  platformAdminName: string;
}

/** An impersonated session: who acts as whom, until when. Never stored anywhere but memory. */
export interface Impersonation {
  id: string;
  staffName: string;
  platformAdminName: string;
  expiresAt: number;
}

/** The sign-in steps of `POST /api/v1/auth/...` (ADR 0010). */
export type SignInStatus = 'SIGNED_IN' | 'CHANGE_PASSWORD' | 'TOTP_REQUIRED' | 'TOTP_ENROLL';

export interface SignInResponse {
  status: SignInStatus;
  accessToken?: string;
  expiresIn?: number;
  challenge?: string;
  totpSecret?: string;
  otpauthUri?: string;
  recoveryCodes?: string[];
}

/** `GET /api/v1/me`. */
export interface Me {
  id: string;
  email: string;
  displayName: string;
  tenantCode: string;
  tenantName: string;
  role: string;
  permissions: string[];
  totpEnabled: boolean;
  impersonatedBy: string | null;
}
