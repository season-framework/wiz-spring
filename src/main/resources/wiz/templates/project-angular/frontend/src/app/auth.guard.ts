import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

export const authGuard: CanActivateFn = async (_route, state) => {
  const session = inject(SessionService);
  const router = inject(Router);
  try {
    return await session.ensure()
      ? true
      : router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  } catch {
    return router.createUrlTree(['/login']);
  }
};
