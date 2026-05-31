import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import {
  provideHttpClient,
  withFetch,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';

import { routes } from './app.routes';
import { adminBearerInterceptor } from './core/admin-bearer.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    // XSRF: auto-attach X-XSRF-TOKEN (XSRF-TOKEN cookie) for session-based mutations (e.g. logout).
    // adminBearerInterceptor: attach Authorization: Bearer for the stateless admin API.
    provideHttpClient(
      withFetch(),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
      withInterceptors([adminBearerInterceptor]),
    ),
  ],
};
