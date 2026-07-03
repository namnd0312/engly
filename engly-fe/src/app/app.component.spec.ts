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

  /** ngOnInit fires AuthService.loadMe(); flush it so HttpTestingController stays clean. */
  function flushMe(status = 401) {
    const req = httpTesting.expectOne('/api/me');
    if (status === 200) {
      req.flush({ sub: 'u-1', email: 'a@b.com', name: 'Alice' });
    } else {
      req.flush(null, { status, statusText: 'Unauthorized' });
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
    expect(compiled.querySelector('.app-title')?.textContent).toContain('Engly');
    expect(compiled.querySelector('.auth-btn')?.textContent).toContain('Đăng nhập');
    httpTesting.verify();
  });

  it('shows the user name and Logout button when authenticated', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushMe(200);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.app-user')?.textContent).toContain('Alice');
    // Authenticated view renders multiple auth buttons (Profile, Logout); assert one is Logout.
    const labels = Array.from(compiled.querySelectorAll('.auth-btn')).map((b) => b.textContent ?? '');
    expect(labels.some((t) => t.includes('Logout'))).toBeTrue();
    httpTesting.verify();
  });
});
