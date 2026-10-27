import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { PLATFORM_API } from '../../core/api/api.config';
import {
  CreateTenant,
  CreatedTenant,
  Impersonation,
  ImpersonationStarted,
  Gate1,
  Invitation,
  Staff,
  StaffPage,
  Tenant,
} from '../../shared/models';

const TENANTS = `${PLATFORM_API}/tenants`;

/**
 * `/api/platform/...` (docs/07 Platform layer). Answers that carry a temporary password (a new tenant's
 * Owner, a reissued invitation) are shown once and kept nowhere.
 */
@Injectable({ providedIn: 'root' })
export class TenantsService {
  private readonly http = inject(HttpClient);

  list(): Observable<Tenant[]> {
    return this.http.get<Tenant[]>(TENANTS);
  }

  /** A new tenant in TRIAL with its Owner invited; the Owner's temporary password is in the answer only. */
  create(request: CreateTenant): Observable<CreatedTenant> {
    return this.http.post<CreatedTenant>(TENANTS, request);
  }

  activate(id: string): Observable<Tenant> {
    return this.http.post<Tenant>(`${TENANTS}/${id}/activate`, null);
  }

  /** Staff and portal sign-ins are refused and jobs skip the tenant; RADIUS and callbacks continue. */
  suspend(id: string, reason: string): Observable<Tenant> {
    return this.http.post<Tenant>(`${TENANTS}/${id}/suspend`, { reason });
  }

  reinstate(id: string): Observable<Tenant> {
    return this.http.post<Tenant>(`${TENANTS}/${id}/reinstate`, null);
  }

  /** ADR 0014: the Owner may import lines already active until {@code until}, at most 30 days ahead. */
  openCutoverWindow(id: string, until: string): Observable<Tenant> {
    return this.http.put<Tenant>(`${TENANTS}/${id}/cutover-window`, { until });
  }

  closeCutoverWindow(id: string): Observable<Tenant> {
    return this.http.delete<Tenant>(`${TENANTS}/${id}/cutover-window`);
  }

  staff(id: string): Observable<Staff[]> {
    return this.http
      .get<StaffPage>(`${TENANTS}/${id}/staff`, { params: new HttpParams().set('size', 200) })
      .pipe(map((page) => page.items ?? []));
  }

  /** An unused invitation only (409 for an active member); the new temporary password is shown once. */
  reissueInvitation(id: string, staffId: string): Observable<Invitation> {
    return this.http.post<Invitation>(`${TENANTS}/${id}/staff/${staffId}/invitation`, null);
  }

  /** Gate 1 over the last {@code days} days (ADR 0018), measured by the platform. */
  gate1(id: string, days: number): Observable<Gate1> {
    return this.http.get<Gate1>(`${TENANTS}/${id}/gate1`, {
      params: new HttpParams().set('days', days),
    });
  }

  /**
   * ADR 0017: act as an active staff member for at most 30 minutes, with a reason or ticket the tenant reads in
   * its audit log. The answer carries a single-use hand-off code (60 seconds) and the admin app link with it.
   */
  impersonate(
    tenantId: string,
    staffUserId: string,
    reason: string,
    minutes: number,
  ): Observable<ImpersonationStarted> {
    return this.http.post<ImpersonationStarted>(`${TENANTS}/${tenantId}/impersonations`, {
      staffUserId,
      reason,
      minutes,
    });
  }

  /** The last 100 impersonations, every tenant. */
  impersonations(): Observable<Impersonation[]> {
    return this.http.get<Impersonation[]>(`${PLATFORM_API}/impersonations`);
  }

  endImpersonation(id: string): Observable<Impersonation> {
    return this.http.post<Impersonation>(`${PLATFORM_API}/impersonations/${id}/end`, null);
  }
}
