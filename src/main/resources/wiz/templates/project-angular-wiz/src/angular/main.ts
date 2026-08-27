import { platformBrowser } from '@angular/platform-browser';
import { AppModule } from './app/app.module';
import { loadRuntimeConfig } from './wiz';

loadRuntimeConfig()
  .catch((error) => console.warn('[wiz] runtime config unavailable; using /api', error))
  .then(() => platformBrowser().bootstrapModule(AppModule))
  .catch((error) => console.error(error));
