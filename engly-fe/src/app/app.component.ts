import { Component, OnInit, inject } from '@angular/core';
import { RouterOutlet, Router } from '@angular/router';
import { AuthService } from './core/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent implements OnInit {
  title = 'frontend';

  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly user = this.auth.user;
  readonly isAuthenticated = this.auth.isAuthenticated;
  readonly isAdmin = this.auth.isAdmin;

  ngOnInit(): void {
    // Non-blocking: anonymous users can still use verb lookup.
    this.auth.loadMe();
  }

  /** Navigate to the profile page (Router service avoids a RouterLink import). */
  goToProfile(): void {
    this.router.navigate(['/profile']);
  }

  /** Navigate to the admin page (only shown to admins). */
  goToAdmin(): void {
    this.router.navigate(['/admin']);
  }

  /** Navigate to the study deck list (shown to authenticated users). */
  goToStudy(): void {
    this.router.navigate(['/study']);
  }

  login(): void {
    this.auth.login();
  }

  logout(): void {
    this.auth.logout();
  }
}
