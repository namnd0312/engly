import { Component, OnInit, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
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
  readonly user = this.auth.user;
  readonly isAuthenticated = this.auth.isAuthenticated;

  ngOnInit(): void {
    // Non-blocking: anonymous users can still use verb lookup.
    this.auth.loadMe();
  }

  login(): void {
    this.auth.login();
  }

  logout(): void {
    this.auth.logout();
  }
}
