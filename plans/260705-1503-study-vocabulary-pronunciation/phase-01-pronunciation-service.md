---
phase: 1
title: Pronunciation Service
status: completed
effort: S
---

# Phase 1: Pronunciation Service

## Overview

Reusable Angular service wrapping the browser Web Speech API (`speechSynthesis`).
Owns support detection, English voice selection, and safe speak (cancels any in-flight
utterance). Lives in `core/` alongside `auth.service.ts` (existing cross-cutting pattern).

## Requirements

- Functional:
  - `isSupported`: true only when `window.speechSynthesis` + `SpeechSynthesisUtterance` exist.
  - `speak(text: string, lang = 'en-US')`: cancels prior speech, then speaks `text`.
  - Prefer a voice whose `lang` starts with `en`; fall back to setting `utterance.lang`.
  - Handle async voice loading: voices may be empty on first call → resolve lazily via the `voiceschanged` event.
  - No-op safely when unsupported or `text` is blank.
- Non-functional:
  - Zero new dependencies. SSR/test-safe: guard all `window` access.
  - Injectable singleton (`providedIn: 'root'`).

## Architecture

Angular standalone-app service, `providedIn: 'root'`. Wraps the global
`speechSynthesis`. Voice list cached once populated; `voiceschanged` listener attached
in constructor only when supported. `speak()` always calls `cancel()` first so rapid
clicks never queue/overlap.

Pseudocode:

```ts
@Injectable({ providedIn: 'root' })
export class PronunciationService {
  private synth = typeof window !== 'undefined' ? window.speechSynthesis : null;
  private voices: SpeechSynthesisVoice[] = [];

  readonly isSupported =
    !!this.synth && typeof SpeechSynthesisUtterance !== 'undefined';

  constructor() {
    if (!this.isSupported) return;
    this.loadVoices();
    this.synth!.onvoiceschanged = () => this.loadVoices();
  }

  private loadVoices() { this.voices = this.synth!.getVoices(); }

  private pickEnglishVoice(lang: string) {
    return (
      this.voices.find(v => v.lang === lang) ??
      this.voices.find(v => v.lang?.toLowerCase().startsWith('en')) ??
      null
    );
  }

  speak(text: string, lang = 'en-US'): void {
    if (!this.isSupported || !text?.trim()) return;
    this.synth!.cancel();                    // stop any in-flight utterance
    const u = new SpeechSynthesisUtterance(text.trim());
    u.lang = lang;
    const voice = this.pickEnglishVoice(lang);
    if (voice) u.voice = voice;
    this.synth!.speak(u);
  }
}
```

## Related Code Files

- Create: `engly-fe/src/app/core/pronunciation.service.ts`
- Create: `engly-fe/src/app/core/pronunciation.service.spec.ts`
- Reference (pattern/style): `engly-fe/src/app/core/auth.service.ts`

## Implementation Steps

1. Create `pronunciation.service.ts` per pseudocode above; add concise JSDoc header
   matching the style of other `core/` services.
2. Guard every `window` / global access so unit tests and any SSR path don't throw.
3. Create `pronunciation.service.spec.ts`:
   - Stub `window.speechSynthesis` (`speak`, `cancel`, `getVoices`, `onvoiceschanged`)
     and a global `SpeechSynthesisUtterance` in `beforeEach`; restore in `afterEach`.
   - `speak()` calls `cancel()` then `speak()` with the trimmed term.
   - `speak('')` / blank is a no-op.
   - When unsupported (`speechSynthesis` undefined), `isSupported` is false and `speak()` no-ops.
   - English voice is preferred when `getVoices()` returns mixed langs.

## Success Criteria

- [ ] `PronunciationService` compiles; `ng build` (or `npm run build`) clean.
- [ ] `speak()` cancels before speaking; blank/unsupported no-op verified by tests.
- [ ] English voice selection covered by a test.
- [ ] All new unit tests green (`npm test` for the service spec).

## Risk Assessment

- **Voices empty on first call** (Chrome loads async) → mitigated by `voiceschanged`
  listener + falling back to `utterance.lang`, which still speaks correctly.
- **Browser support gaps** (older Safari/iOS quirks) → `isSupported` gates the UI so
  the button simply hides; no runtime error.
