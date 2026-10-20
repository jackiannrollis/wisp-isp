import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

/**
 * A fresh Idempotency-Key (conventions.md, API: writes that move money or change access). Create one
 * when a dialog opens and reuse it for every retry of that same submission, so a double click or a
 * retried request is applied once.
 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export function idempotent(key: string): { headers: HttpHeaders } {
  return { headers: new HttpHeaders({ 'Idempotency-Key': key }) };
}

/**
 * The message to show for a failed request: the RFC 9457 problem detail the API wrote for people, the
 * first field error of a validation problem, or a plain fallback. Never a stack trace.
 */
export function errorMessage(error: unknown, fallback = 'That did not work. Try again.'): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'The server cannot be reached. Check your connection and try again.';
    }
    const problem = error.error as {
      detail?: string;
      errors?: { field?: string; message?: string }[];
    } | null;
    const field = problem?.errors?.[0];
    if (field?.message) {
      return field.field ? `${field.field}: ${field.message}` : field.message;
    }
    if (problem?.detail) {
      return problem.detail;
    }
    if (error.status === 403) {
      return 'You do not have permission for this action.';
    }
  }
  return fallback;
}

const SHILLINGS = /^\s*(-)?(\d{1,9})(?:\.(\d{1,2}))?\s*$/;

/**
 * Shillings typed by a person ("2,500" or "2500.50") to integer cents (B2: money enters as minor
 * units at the API boundary). Parsed as text, never through floating point; null when not a valid
 * amount.
 */
export function shillingsToMinor(text: string, allowNegative = false): number | null {
  const match = SHILLINGS.exec(text.replace(/,/g, ''));
  if (!match || (match[1] && !allowNegative)) {
    return null;
  }
  const cents = Number(match[2]) * 100 + Number((match[3] ?? '').padEnd(2, '0'));
  return match[1] ? -cents : cents;
}
