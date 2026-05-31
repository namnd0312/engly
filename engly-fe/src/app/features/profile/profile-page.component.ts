import { Component, inject } from '@angular/core';
import { AuthService } from '../../core/auth.service';

/**
 * Plain-text profile view. Reads identity straight from AuthService signals
 * (populated once at app start by AppComponent.loadMe()) — no own HTTP call.
 */
@Component({
  selector: 'app-profile-page',
  templateUrl: './profile-page.component.html',
})
export class ProfilePageComponent {
  private readonly auth = inject(AuthService);

  readonly user = this.auth.user;
  readonly isAuthenticated = this.auth.isAuthenticated;

  login(): void {
    this.auth.login();
  }
}
