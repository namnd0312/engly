import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AdminFormPageComponent } from './admin-form-page.component';

describe('AdminFormPageComponent (create mode)', () => {
  let fixture: ComponentFixture<AdminFormPageComponent>;
  let component: AdminFormPageComponent;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminFormPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminFormPageComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges(); // ngOnInit → create mode (no id), no HTTP call
  });

  afterEach(() => httpTesting.verify());

  it('starts in create mode with an invalid (empty) form', () => {
    expect(component.editId()).toBeNull();
    expect(component.form.invalid).toBeTrue();
  });

  it('becomes valid once required fields are filled', () => {
    component.form.patchValue({
      baseForm: 'swim',
      pastSimple: 'swam',
      pastParticiple: 'swum',
      meaningVi: 'bơi',
    });
    expect(component.form.valid).toBeTrue();
  });

  it('submits a POST to /api/admin/verbs and navigates on success', () => {
    component.form.patchValue({
      baseForm: 'swim',
      pastSimple: 'swam',
      pastParticiple: 'swum',
      meaningVi: 'bơi',
    });
    component.onSubmit();

    const req = httpTesting.expectOne('/api/admin/verbs');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.baseForm).toBe('swim');
    req.flush({
      id: 5,
      baseForm: 'swim',
      pastSimple: 'swam',
      pastParticiple: 'swum',
      pastSimpleAlt: null,
      pastParticipleAlt: null,
      ipaBase: null,
      ipaPast: null,
      ipaPp: null,
      meaningVi: 'bơi',
      verified: false,
    });
    expect(component.serverError()).toBeNull();
  });

  it('shows the duplicate message on a 409 response', () => {
    component.form.patchValue({
      baseForm: 'go',
      pastSimple: 'went',
      pastParticiple: 'gone',
      meaningVi: 'đi',
    });
    component.onSubmit();

    httpTesting
      .expectOne('/api/admin/verbs')
      .flush({ message: 'base_form already exists' }, { status: 409, statusText: 'Conflict' });

    expect(component.serverError()).toBe('base_form already exists');
    expect(component.submitting()).toBeFalse();
  });

  it('does not submit when the form is invalid', () => {
    component.onSubmit(); // empty form
    httpTesting.expectNone('/api/admin/verbs');
    expect(component.submitting()).toBeFalse();
  });
});
