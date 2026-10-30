import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { Auth, AUTH_API } from './auth';

/**
 * Sends the access token with API calls. When it has expired, or the API rejects it, gets a new one
 * from the session and retries once. Auth endpoints use the session cookie instead, and never get it.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(Auth);
  if (!request.url.startsWith('/api/') || request.url.startsWith(AUTH_API) || request.headers.has('Authorization')) {
    return next(request);
  }
  const send = (token: string | null) => next(token ? withToken(request, token) : request);
  const token = auth.token();
  if (!token) {
    // Signed in but the token ran out (the laptop slept through the renewal): renew first
    return auth.signedIn() ? from(auth.refresh()).pipe(switchMap(send)) : next(request);
  }
  return send(token).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse && error.status === 401)) {
        return throwError(() => error);
      }
      return from(auth.refresh()).pipe(
        switchMap((fresh) => (fresh ? send(fresh) : throwError(() => error))),
      );
    }),
  );
};

function withToken<T>(request: HttpRequest<T>, token: string): HttpRequest<T> {
  return request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}
