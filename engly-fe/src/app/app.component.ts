import { Component, OnInit, computed, inject, signal } from '@angular/core';
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

  /** User dropdown menu open state (avatar chip → Profile / Logout). */
  readonly menuOpen = signal(false);

  /** Best available display label for the header chip. */
  readonly displayName = computed(() => this.user()?.name || this.user()?.email || '');

  /** Uppercase first letter for the avatar fallback when no image is set. */
  readonly avatarInitial = computed(() => (this.displayName() || '?').charAt(0).toUpperCase());

  ngOnInit(): void {
    // Non-blocking: anonymous users can still use verb lookup.
    this.auth.loadMe();
  }

  toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  closeMenu(): void {
    this.menuOpen.set(false);
  }

  /** Navigate to the profile page (Router service avoids a RouterLink import). */
  goToProfile(): void {
    this.closeMenu();
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

  /** Navigate to the progress dashboard (shown to authenticated users). */
  goToProgress(): void {
    this.router.navigate(['/progress']);
  }

  goToLogin(): void {
    this.router.navigate(['/login']);
  }

  logout(): void {
    this.closeMenu();
    this.auth.logout();
  }
}
