---
phase: 2
title: Wire Speaker Button + Tests
status: completed
effort: S
---

# Phase 2: Wire Speaker Button + Tests

## Overview

Add a speaker-icon button to the flashcard front face that calls
`PronunciationService.speak(term)`. Inline SVG icon + plain CSS, matching existing
conventions. Update component spec.

Depends on Phase 1 (`PronunciationService`).

## Requirements

- Functional:
  - Speaker button rendered next to `.card-term` on the front face.
  - Click → speaks the current card's English `term`.
  - Button hidden when `!pronunciation.isSupported`.
  - `stopPropagation` on click so tapping the icon never triggers the card flip.
- Non-functional:
  - `type="button"`, `aria-label="Phát âm"`, focus-visible outline, keyboard-activatable.
  - Inline `<svg viewBox="0 0 20 20" aria-hidden="true">` (repo convention — no icon lib).

## Architecture

Inject `PronunciationService` into `FlashcardSessionPageComponent`. Expose it (or a
thin `pronounce()` method + `canSpeak` getter) to the template. Button sits in a flex
row with the term. Speech is fire-and-forget; no new signals/state needed.

Template (front face, around current `flashcard-session-page.component.html:54`):

```html
<div class="term-row">
  <p class="card-term">{{ currentCard()!.term }}</p>
  @if (pronunciation.isSupported) {
    <button
      type="button"
      class="speak-btn"
      aria-label="Phát âm"
      (click)="pronounce($event)"
    >
      <svg viewBox="0 0 20 20" aria-hidden="true" width="20" height="20">
        <!-- speaker glyph path(s) -->
      </svg>
    </button>
  }
</div>
```

Component:

```ts
protected readonly pronunciation = inject(PronunciationService);

pronounce(event: Event): void {
  event.stopPropagation();
  const term = this.currentCard()?.term;
  if (term) this.pronunciation.speak(term);
}
```

## Related Code Files

- Modify: `engly-fe/src/app/features/study/flashcard-session-page.component.ts`
  (inject service, add `pronounce()`).
- Modify: `engly-fe/src/app/features/study/flashcard-session-page.component.html`
  (wrap term in `.term-row`, add speaker button).
- Modify: `engly-fe/src/app/features/study/flashcard-session-page.component.css`
  (`.term-row`, `.speak-btn`, hover/focus states).
- Modify: `engly-fe/src/app/features/study/flashcard-session-page.component.spec.ts`
  (button render + `pronounce()` behavior).
- Reference: `engly-fe/src/app/app.component.html` (inline-SVG button markup pattern).

## Implementation Steps

1. Inject `PronunciationService` and add `pronounce(event)` to the component `.ts`.
2. Edit the HTML: wrap `.card-term` in `.term-row`, add the guarded speaker button
   with inline speaker SVG. Keep it on the front face next to the term.
3. Add CSS: align term + button in a row; button is icon-only, muted color, hover/
   focus-visible states consistent with `.flip-btn` styling.
4. Update the component spec:
   - Provide a `PronunciationService` spy (`isSupported = true`, `speak` spy).
   - Button renders when supported; hidden when `isSupported = false`.
   - Clicking the button calls `speak` with the current term and does NOT flip the card.
5. Run the study specs; fix regressions rather than weakening assertions.

## Success Criteria

- [ ] Speaker button appears next to the term; hidden when unsupported.
- [ ] Click speaks the English term and does not flip the card.
- [ ] `pronounce()` covered by component spec; existing flashcard tests still pass.
- [ ] `npm run build` clean; manual smoke test in Chrome confirms audible pronunciation.

## Risk Assessment

- **Click bubbling flips the card** → mitigated by `event.stopPropagation()` + a test
  asserting `flipped()` stays false.
- **Icon visual mismatch** → reuse existing SVG button sizing/color tokens from
  `app.component`/`.flip-btn`; keep the glyph simple.
- **Autoplay/gesture policies** → speech is user-initiated (a click), so browser
  gesture requirements are satisfied.
