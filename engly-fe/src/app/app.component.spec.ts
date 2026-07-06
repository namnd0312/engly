import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    httpTesting = TestBed.inject(HttpTestingController);
  });

  /**
   * ngOnInit fires AuthService.loadMe(); flush it so HttpTestingController stays clean.
   * On 401 the service attempts one silent /api/auth/refresh (access-cookie recovery), so the
   * anonymous path also flushes that follow-up refresh (also 401 → stays anonymous).
   */
  function flushMe(status = 401) {
    const req = httpTesting.expectOne('/api/me');
    if (status === 200) {
      req.flush({ email: 'a@b.com', name: 'Alice' });
    } else {
      req.flush(null, { status, statusText: 'Unauthorized' });
      httpTesting
        .expectOne('/api/auth/refresh')
        .flush(null, { status: 401, statusText: 'Unauthorized' });
    }
  }

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it(`should have the 'frontend' title`, () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance.title).toEqual('frontend');
  });

  it('should render app header with Engly title and a Login button when anonymous', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges(); // triggers ngOnInit → loadMe()
    flushMe(401);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.brand-name')?.textContent).toContain('Engly');
    expect(compiled.querySelector('.nav-btn')?.textContent).toContain('Đăng nhập');
    httpTesting.verify();
  });

  it('shows the user name and Logout button when authenticated', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushMe(200);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    // The user chip shows the display name; Logout lives in the dropdown opened from the chip.
    expect(compiled.querySelector('.chip-name')?.textContent).toContain('Alice');

    (compiled.querySelector('.user-chip') as HTMLButtonElement).click();
    fixture.detectChanges();

    const items = Array.from(compiled.querySelectorAll('.menu-item')).map((b) => b.textContent ?? '');
    expect(items.some((t) => t.includes('Logout'))).toBeTrue();
    expect(items.some((t) => t.includes('Profile'))).toBeTrue();
    httpTesting.verify();
  });
});
