/** Tenant branding as returned by `GET /api/v1/branding`. */
export interface Branding {
  name: string;
  logoUrl: string | null;
  primaryColor: string;
}
