/**
 * Typed API models generated from the backend OpenAPI spec (`npm run api:generate`), shared with the
 * admin and portal apps.
 */
import type { components } from '@api/schema';

export type SignInResponse = components['schemas']['SignIn'];
export type Tenant = components['schemas']['TenantRef'];
export type CreateTenant = components['schemas']['CreateTenant'];
export type CreatedTenant = components['schemas']['Created'];
export type Invitation = components['schemas']['Invitation'];
export type Staff = components['schemas']['StaffDto'];
export type StaffPage = components['schemas']['PageResponseStaffDto'];
export type Impersonation = components['schemas']['ImpersonationDto'];
export type Gate1 = components['schemas']['Gate1Dto'];

/** The claims the console reads from its own token (never trusted: the server checks every call). */
export interface PlatformClaims {
  sub?: string;
  typ?: string;
  exp?: number;
}
