import { HttpErrorResponse } from '@angular/common/http';

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
