import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ProfileApiService } from './profile-api.service';

describe('ProfileApiService', () => {
  let service: ProfileApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProfileApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('updateProfile() PATCHes /api/profile with credentials', () => {
    service.updateProfile('New Name').subscribe();

    const req = httpTesting.expectOne('/api/profile');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.withCredentials).toBeTrue();
    expect(req.request.body).toEqual({ displayName: 'New Name' });
    req.flush({});
  });

  it('requestAvatarUploadUrl() POSTs the content type', () => {
    service.requestAvatarUploadUrl('image/png').subscribe();

    const req = httpTesting.expectOne('/api/profile/avatar/upload-url');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ contentType: 'image/png' });
    req.flush({ uploadUrl: 'http://s3/put', objectKey: 'k', expiresAt: 'now' });
  });

  it('uploadToS3() PUTs the raw file to the absolute URL without cookies or XSRF header', () => {
    const file = new File(['x'], 'a.png', { type: 'image/png' });
    service.uploadToS3('http://localhost:4566/engly-avatars/k', file).subscribe();

    const req = httpTesting.expectOne('http://localhost:4566/engly-avatars/k');
    expect(req.request.method).toBe('PUT');
    // S3 presigned PUT: signature covers Content-Type; cookies/XSRF must NOT be attached.
    expect(req.request.headers.get('Content-Type')).toBe('image/png');
    expect(req.request.withCredentials).toBeFalse();
    expect(req.request.headers.has('X-XSRF-TOKEN')).toBeFalse();
    expect(req.request.body).toBe(file);
    req.flush(null);
  });

  it('confirmAvatar() POSTs the object key and returns the fresh identity', () => {
    let name: string | null | undefined;
    service.confirmAvatar('avatars/u/x/original').subscribe((u) => (name = u.name));

    const req = httpTesting.expectOne('/api/profile/avatar/confirm');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ objectKey: 'avatars/u/x/original' });
    req.flush({
      email: null, name: 'Alice', isAdmin: false,
      avatarUrl: 'http://s3/256.webp', avatarThumbUrl: 'http://s3/64.webp',
    });

    expect(name).toBe('Alice');
  });
});
