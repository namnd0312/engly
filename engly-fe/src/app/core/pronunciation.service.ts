import { Injectable } from '@angular/core';

/**
 * Text-to-speech client backed by the browser Web Speech API (`speechSynthesis`).
 * Zero dependencies, no backend, no network — used to pronounce vocabulary terms aloud.
 *
 * - isSupported: true only when the browser exposes speechSynthesis + SpeechSynthesisUtterance.
 * - speak(): cancels any in-flight utterance first, so rapid clicks never queue/overlap.
 * - Prefers an English voice; voices load asynchronously (Chrome), refreshed via `voiceschanged`.
 */
@Injectable({ providedIn: 'root' })
export class PronunciationService {
  private readonly synth: SpeechSynthesis | null =
    typeof window !== 'undefined' ? window.speechSynthesis : null;

  private voices: SpeechSynthesisVoice[] = [];

  /** Whether the current browser can speak text at all. Gates the UI control. */
  readonly isSupported: boolean =
    !!this.synth && typeof SpeechSynthesisUtterance !== 'undefined';

  constructor() {
    if (!this.isSupported) return;
    this.loadVoices();
    // Voice list is often empty on first read (loaded async); refresh when ready.
    this.synth!.onvoiceschanged = () => this.loadVoices();
  }

  private loadVoices(): void {
    this.voices = this.synth!.getVoices();
  }

  /** Pick an exact-lang match, else any English voice, else null (browser default). */
  private pickEnglishVoice(lang: string): SpeechSynthesisVoice | null {
    return (
      this.voices.find((v) => v.lang === lang) ??
      this.voices.find((v) => v.lang?.toLowerCase().startsWith('en')) ??
      null
    );
  }

  /** Speak `text` aloud in the given language. No-op when unsupported or text is blank. */
  speak(text: string, lang = 'en-US'): void {
    if (!this.isSupported || !text?.trim()) return;
    this.synth!.cancel(); // stop any in-flight utterance so clicks don't overlap
    const utterance = new SpeechSynthesisUtterance(text.trim());
    utterance.lang = lang;
    const voice = this.pickEnglishVoice(lang);
    if (voice) utterance.voice = voice;
    this.synth!.speak(utterance);
  }
}
