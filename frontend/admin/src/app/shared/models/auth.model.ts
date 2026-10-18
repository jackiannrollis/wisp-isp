/** Claims the frontend reads from the access token. The server remains the authority. */
export interface TokenClaims {
  sub?: string;
  tenant_id?: string;
  permissions?: string[];
  exp?: number;
}
