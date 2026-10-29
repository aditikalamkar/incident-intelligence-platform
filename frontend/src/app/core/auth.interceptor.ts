import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { Auth } from './auth';

/** Sends the user's token with API calls, and signs out when the API no longer accepts it. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(Auth);
  const token = auth.token();
  const authorized =
    token && request.url.startsWith('/api/') && !request.headers.has('Authorization')
      ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : request;
  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && authorized !== request) {
        auth.logout(true);
      }
      return throwError(() => error);
    }),
  );
};
