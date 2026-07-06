import { PronunciationService } from './pronunciation.service';

/**
 * Runs under Karma + real Chrome, where window.speechSynthesis genuinely exists,
 * so we spy on the real API rather than faking the DOM. The service takes no DI
 * deps, so we construct it directly to control voice state per test.
 */
describe('PronunciationService', () => {
  let speakSpy: jasmine.Spy;
  let cancelSpy: jasmine.Spy;

  const voice = (lang: string): SpeechSynthesisVoice => ({ lang }) as SpeechSynthesisVoice;

  beforeEach(() => {
    speakSpy = spyOn(window.speechSynthesis, 'speak').and.stub();
    cancelSpy = spyOn(window.speechSynthesis, 'cancel').and.stub();
  });

  it('is supported in the test browser (Chrome exposes speechSynthesis)', () => {
    expect(new PronunciationService().isSupported).toBe(true);
  });

  it('cancels any in-flight utterance before speaking', () => {
    new PronunciationService().speak('hello');
    expect(cancelSpy).toHaveBeenCalledTimes(1);
    expect(speakSpy).toHaveBeenCalledTimes(1);
    expect(cancelSpy).toHaveBeenCalledBefore(speakSpy);
  });

  it('speaks the trimmed term with the requested language', () => {
    new PronunciationService().speak('  run  ');
    const utterance = speakSpy.calls.mostRecent().args[0] as SpeechSynthesisUtterance;
    expect(utterance.text).toBe('run');
    expect(utterance.lang).toBe('en-US');
  });

  it('is a no-op for blank text', () => {
    const service = new PronunciationService();
    service.speak('');
    service.speak('   ');
    expect(speakSpy).not.toHaveBeenCalled();
    expect(cancelSpy).not.toHaveBeenCalled();
  });

  // Real Chrome rejects plain objects assigned to utterance.voice, so we intercept
  // the setter to capture which voice the service picked (fake voices never reach the DOM).
  function captureAssignedVoice(): { get(): SpeechSynthesisVoice | null | undefined } {
    let assigned: SpeechSynthesisVoice | null | undefined;
    spyOnProperty(SpeechSynthesisUtterance.prototype, 'voice', 'set').and.callFake(function (
      this: SpeechSynthesisUtterance,
      v: SpeechSynthesisVoice | null,
    ) {
      assigned = v;
    });
    return { get: () => assigned };
  }

  it('prefers the exact-lang English voice when voices are mixed', () => {
    spyOn(window.speechSynthesis, 'getVoices').and.returnValue([
      voice('vi-VN'),
      voice('en-GB'),
      voice('en-US'),
    ]);
    const picked = captureAssignedVoice();
    new PronunciationService().speak('word');
    expect(picked.get()?.lang).toBe('en-US');
  });

  it('falls back to a generic English voice when no exact match exists', () => {
    spyOn(window.speechSynthesis, 'getVoices').and.returnValue([
      voice('vi-VN'),
      voice('en-GB'),
    ]);
    const picked = captureAssignedVoice();
    new PronunciationService().speak('word'); // default lang en-US, no en-US voice present
    expect(picked.get()?.lang).toBe('en-GB');
  });
});
