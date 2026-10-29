import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth';

export const authGuard: CanActivateFn = () =>
  inject(Auth).signedIn() || inject(Router).createUrlTree(['/login']);
