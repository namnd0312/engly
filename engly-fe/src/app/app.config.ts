import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import {
  provideHttpClient,
  withFetch,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';

import { routes } from './app.routes';
import { authRefreshInterceptor } from './core/auth-refresh.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    // XSRF: auto-attach X-XSRF-TOKEN (XSRF-TOKEN cookie) for all mutating requests.
    // authRefreshInterceptor: transparently refresh the access cookie on 401 and retry once.
    provideHttpClient(
      withFetch(),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
      withInterceptors([authRefreshInterceptor]),
    ),
  ],
};
