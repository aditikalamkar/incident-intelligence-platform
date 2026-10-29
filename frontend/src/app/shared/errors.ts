import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from '../core/api.models';

/** A message for the user, using the API's explanation when it gives one. */
export function errorMessage(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) {
    return 'Something went wrong. Please try again.';
  }
  if (error.status === 0) {
    return 'Cannot reach OpsPilot. Check your connection and try again.';
  }
  const problem = error.error as Partial<ProblemDetail> | null;
  if (problem && typeof problem === 'object' && typeof problem.detail === 'string') {
    return problem.detail;
  }
  return `The request failed (HTTP ${error.status}).`;
}
