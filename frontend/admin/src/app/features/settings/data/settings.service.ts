import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, of, throwError } from 'rxjs';

import { API_BASE } from '../../../core/api/api.config';
import { components } from '../../../shared/models';

export type MpesaSettings = components['schemas']['MpesaSettingsView'];
export type MpesaRequest = components['schemas']['MpesaSettingsRequest'];
export type SmsSettings = components['schemas']['SmsSettingsView'];
export type SmsRequest = components['schemas']['SmsSettingsRequest'];
export type StoredSecret = components['schemas']['StoredSecret'];
export type Template = components['schemas']['TemplateView'];
export type Placeholder = components['schemas']['PlaceholderView'];

/**
 * `/api/v1/settings/...`: M-Pesa and Africa's Talking accounts (settings.secrets; credentials are
 * write-only, T4), and SMS templates (settings.notifications, M3). A missing account is `null`.
 */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/settings`;

  mpesa(): Observable<MpesaSettings | null> {
    return this.http.get<MpesaSettings>(`${this.base}/mpesa`).pipe(notFoundAsNull);
  }

  saveMpesa(request: MpesaRequest): Observable<MpesaSettings> {
    return this.http.put<MpesaSettings>(`${this.base}/mpesa`, request);
  }

  /** Calls Safaricom's C2B RegisterURL with the tenant's credentials. */
  registerUrls(): Observable<MpesaSettings> {
    return this.http.post<MpesaSettings>(`${this.base}/mpesa/register-urls`, null);
  }

  sms(): Observable<SmsSettings | null> {
    return this.http.get<SmsSettings>(`${this.base}/notifications`).pipe(notFoundAsNull);
  }

  saveSms(request: SmsRequest): Observable<SmsSettings> {
    return this.http.put<SmsSettings>(`${this.base}/notifications`, request);
  }

  /** Which secrets are set and when; never their values. */
  secrets(): Observable<StoredSecret[]> {
    return this.http.get<StoredSecret[]>(`${this.base}/secrets`);
  }

  templates(): Observable<Template[]> {
    return this.http.get<Template[]>(`${this.base}/notifications/templates`);
  }

  placeholders(): Observable<Placeholder[]> {
    return this.http.get<Placeholder[]>(`${this.base}/notifications/placeholders`);
  }

  preview(key: string, body: string): Observable<Template> {
    return this.http.post<Template>(`${this.base}/notifications/templates/${key}/preview`, {
      body,
    });
  }

  saveTemplate(key: string, body: string): Observable<Template> {
    return this.http.put<Template>(`${this.base}/notifications/templates/${key}`, { body });
  }

  resetTemplate(key: string): Observable<Template> {
    return this.http.delete<Template>(`${this.base}/notifications/templates/${key}`);
  }
}

function notFoundAsNull<T>(source: Observable<T>): Observable<T | null> {
  return source.pipe(
    catchError((e: unknown) =>
      (e as { status?: number }).status === 404 ? of(null) : throwError(() => e),
    ),
  );
}
