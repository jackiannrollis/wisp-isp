/** Claims the frontend reads from the access token. The server remains the authority. */
export interface TokenClaims {
  sub?: string;
  tenant_id?: string;
  exp?: number;
}

/** The portal sign-in steps of `POST /api/v1/portal-auth/...` (ADR 0010, Q7 default). */
export type SignInStatus = 'SIGNED_IN' | 'PIN_SETUP' | 'CHOOSE_ACCOUNT';

/** One account sharing the phone, each with its own PIN_SETUP challenge. */
export interface SignInAccount {
  number: string;
  name: string;
  challenge: string;
}

export interface SignInResponse {
  status: SignInStatus;
  accessToken?: string;
  expiresIn?: number;
  challenge?: string;
  accounts?: SignInAccount[];
}
