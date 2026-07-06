import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpEvent, HttpEventType, HttpResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { ProfilePageComponent } from './profile-page.component';
import { ProfileApiService } from './data/profile-api.service';
import { AuthService, UserInfo } from '../../core/auth.service';

const ALICE: UserInfo = {
  email: 'a@b.com', name: 'Alice', isAdmin: false,
  avatarUrl: null, avatarThumbUrl: null,
};

describe('ProfilePageComponent', () => {
  let fixture: ComponentFixture<ProfilePageComponent>;
  let component: ProfilePageComponent;
  let api: jasmine.SpyObj<ProfileApiService>;
  let auth: jasmine.SpyObj<AuthService> & { user: () => UserInfo | null };

  beforeEach(async () => {
    api = jasmine.createSpyObj<ProfileApiService>('ProfileApiService', [
      'updateProfile', 'requestAvatarUploadUrl', 'uploadToS3', 'confirmAvatar',
    ]);
    auth = jasmine.createSpyObj('AuthService', ['setUser'], { user: () => ALICE });

    await TestBed.configureTestingModule({
      imports: [ProfilePageComponent],
      providers: [
        { provide: ProfileApiService, useValue: api },
        { provide: AuthService, useValue: auth },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProfilePageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('prefills the display name from the current user', () => {
    expect(component.form.getRawValue().displayName).toBe('Alice');
  });

  it('saveName() PATCHes and pushes the new identity into AuthService', () => {
    const renamed = { ...ALICE, name: 'Renamed' };
    api.updateProfile.and.returnValue(of(renamed));

    component.form.setValue({ displayName: 'Renamed' });
    component.saveName();

    expect(api.updateProfile).toHaveBeenCalledWith('Renamed');
    expect(auth.setUser).toHaveBeenCalledWith(renamed);
    expect(component.saveSuccess()).toBeTrue();
  });

  it('saveName() with a blank name does not call the API', () => {
    component.form.setValue({ displayName: '' });
    component.saveName();

    expect(api.updateProfile).not.toHaveBeenCalled();
  });

  it('saveName() surfaces the server error message', () => {
    api.updateProfile.and.returnValue(
      throwError(() => ({ error: { message: 'boom' } })),
    );

    component.form.setValue({ displayName: 'X' });
    component.saveName();

    expect(component.saveError()).toBe('boom');
    expect(component.saving()).toBeFalse();
  });

  it('rejects non-image files before contacting the API', () => {
    const input = document.createElement('input');
    input.type = 'file';
    const pdf = new File(['x'], 'doc.pdf', { type: 'application/pdf' });
    Object.defineProperty(input, 'files', { value: [pdf] });

    component.onFileChange({ target: input } as unknown as Event);

    expect(component.uploadError()).toContain('image');
    expect(api.requestAvatarUploadUrl).not.toHaveBeenCalled();
  });

  it('happy path: upload-url → S3 PUT → confirm → identity updated', () => {
    const withAvatar = { ...ALICE, avatarUrl: 'http://s3/256.webp', avatarThumbUrl: 'http://s3/64.webp' };
    api.requestAvatarUploadUrl.and.returnValue(
      of({ uploadUrl: 'http://s3/put', objectKey: 'avatars/u/x/original', expiresAt: 'now' }),
    );
    const s3Events: HttpEvent<unknown>[] = [
      { type: HttpEventType.UploadProgress, loaded: 50, total: 100 },
      new HttpResponse({ status: 200 }),
    ];
    api.uploadToS3.and.returnValue(of(...s3Events));
    api.confirmAvatar.and.returnValue(of(withAvatar));

    const input = document.createElement('input');
    input.type = 'file';
    const png = new File(['x'], 'me.png', { type: 'image/png' });
    Object.defineProperty(input, 'files', { value: [png] });

    component.onFileChange({ target: input } as unknown as Event);

    expect(api.requestAvatarUploadUrl).toHaveBeenCalledWith('image/png');
    expect(api.uploadToS3).toHaveBeenCalledWith('http://s3/put', png);
    expect(api.confirmAvatar).toHaveBeenCalledWith('avatars/u/x/original');
    expect(auth.setUser).toHaveBeenCalledWith(withAvatar);
    expect(component.uploading()).toBeFalse();
    expect(component.uploadError()).toBeNull();
  });

  it('S3 PUT failure surfaces an error and stops the flow', () => {
    api.requestAvatarUploadUrl.and.returnValue(
      of({ uploadUrl: 'http://s3/put', objectKey: 'k', expiresAt: 'now' }),
    );
    api.uploadToS3.and.returnValue(throwError(() => new Error('network')));

    const input = document.createElement('input');
    input.type = 'file';
    const png = new File(['x'], 'me.png', { type: 'image/png' });
    Object.defineProperty(input, 'files', { value: [png] });

    component.onFileChange({ target: input } as unknown as Event);

    expect(component.uploadError()).toContain('Upload');
    expect(api.confirmAvatar).not.toHaveBeenCalled();
    expect(component.uploading()).toBeFalse();
  });
});
