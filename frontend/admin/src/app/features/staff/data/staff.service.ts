import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { listParams } from '../../../core/api/query-params';
import { Page, TableQuery } from '../../../shared/data-table/data-table.types';
import { components } from '../../../shared/models';

export type Staff = components['schemas']['StaffDto'];
export type Invitation = components['schemas']['Invitation'];
export type Role = components['schemas']['RoleDto'];
export type Permission = components['schemas']['PermissionDto'];

export interface InviteRequest {
  email: string;
  displayName: string;
  phone?: string;
  roleId: string;
}

/**
 * `/api/v1/staff` and `/api/v1/roles` (staff.manage). Temporary passwords come back once and are not
 * idempotent: a replay would show the secret again (conventions.md, API).
 */
@Injectable({ providedIn: 'root' })
export class StaffService {
  private readonly http = inject(HttpClient);

  list(query: TableQuery): Observable<Page<Staff>> {
    return this.http.get<Page<Staff>>(`${API_BASE}/staff`, { params: listParams(query) });
  }

  invite(request: InviteRequest, sendSms: boolean): Observable<Invitation> {
    return this.http.post<Invitation>(`${API_BASE}/staff`, request, {
      params: new HttpParams().set('sendSms', sendSms),
    });
  }

  /** A new temporary password: regenerates an unused invitation or resets a password; sessions end. */
  temporaryPassword(id: string, sendSms: boolean): Observable<Invitation> {
    return this.http.post<Invitation>(`${API_BASE}/staff/${id}/temporary-password`, null, {
      params: new HttpParams().set('sendSms', sendSms),
    });
  }

  disable(id: string, reason: string): Observable<Staff> {
    return this.http.post<Staff>(`${API_BASE}/staff/${id}/disable`, { reason: reason || null });
  }

  enable(id: string): Observable<Staff> {
    return this.http.post<Staff>(`${API_BASE}/staff/${id}/enable`, null);
  }

  changeRole(id: string, roleId: string): Observable<Staff> {
    return this.http.put<Staff>(`${API_BASE}/staff/${id}/role`, { roleId });
  }

  roles(): Observable<Role[]> {
    return this.http.get<Role[]>(`${API_BASE}/roles`);
  }

  permissions(): Observable<Permission[]> {
    return this.http.get<Permission[]>(`${API_BASE}/permissions`);
  }

  createRole(name: string, permissions: string[]): Observable<Role> {
    return this.http.post<Role>(`${API_BASE}/roles`, { name, permissions });
  }

  updateRole(id: string, name: string, permissions: string[]): Observable<Role> {
    return this.http.put<Role>(`${API_BASE}/roles/${id}`, { name, permissions });
  }
}
