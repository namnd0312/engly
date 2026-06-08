# SM-2 Spaced Repetition Algorithm Research

**Date:** 2026-06-01 | **Status:** Complete

---

## 1. Canonical SM-2 Algorithm

**Core Variables per Card:**
- `EF` (Easiness Factor): float, initial 2.5, minimum 1.3
- `n` (Repetition count): int, reset to 0 on fail
- `I` (Interval): int days until next review
- `lapses`: int, count of q < 3

**Easiness Factor Update Formula:**
```
EF' = EF + (0.1 - (5 - q) × (0.08 + (5 - q) × 0.02))
where q ∈ [0,5] = quality grade
floor(EF') to 1.3 (never below)
```

**Interval Rules:**
```
If q < 3 (fail):
  n := 0, I := 1, lapses++
Else (success, q ≥ 3):
  n := n + 1
  if n = 1: I := 1
  if n = 2: I := 6
  if n > 2: I := round(I × EF)
```

Due date: `today + I days`

---

## 2. Anki 4-Button Mapping (MVP-Suitable)

Anki simplifies SM-2 from 6-grade (0–5) to 4-button UX:

| Button | Grade | EF Adjust | Interval Rule | Purpose |
|--------|-------|-----------|---------------|---------|
| **Again** | 0 | -0.2 (hardcap EF=1.3) | Reset n=0, I=1 | Hard lapse |
| **Hard** | 2 | -0.15 (approx) | n>0 but ×0.5 modifier | Struggle, slow growth |
| **Good** | 3 | 0 | Standard I rule | Baseline success |
| **Easy** | 5 | +0.1 (boost) | Standard I rule ×1.3 | Perfect recall |

**Why this mapping:** Reduces decision fatigue, avoids "ease hell" (dropping q=2 too harshly), and preserves SM-2 semantics.

---

## 3. Initial Values & Edge Cases

**Initialization (new card):**
- EF = 2.5
- n = 0
- I = 0 (or null; first review always yields I=1 if q≥3, else I=1 at reset)
- lapses = 0

**First Review:** Always returns I=1 day (n becomes 1), regardless of quality.

**Date Math:**
- Store `due_on` as DATE, not timestamp. Due date = `LocalDate.now() + I days`
- Card is due if `LocalDate.now() >= due_on`

**Lapses:** Log count; optional: suspend card if `lapses >= 3` (anti-burnout).

**EF Floor Enforcement:** After every EF update, `max(1.3, EF)`

---

## 4. Pure Schedulable Function (Java-ish Pseudocode)

```java
/**
 * SM-2 scheduler: pure function, no side effects.
 * Input: current card state + user rating
 * Output: new card state
 */
public class SM2State {
    public double easiness;      // EF
    public int reps;             // n
    public int interval;         // I (days)
    public int lapses;           // lapse count
    
    public SM2State schedule(int quality) {
        SM2State next = new SM2State();
        next.lapses = this.lapses;
        
        if (quality < 3) {
            // Failure: reset
            next.reps = 0;
            next.interval = 1;
            next.easiness = Math.max(1.3, 
                this.easiness + (0.1 - (5 - quality) * 
                (0.08 + (5 - quality) * 0.02)));
            next.lapses++;
        } else {
            // Success: advance
            next.reps = this.reps + 1;
            next.easiness = Math.max(1.3,
                this.easiness + (0.1 - (5 - quality) * 
                (0.08 + (5 - quality) * 0.02)));
            
            if (next.reps == 1) {
                next.interval = 1;
            } else if (next.reps == 2) {
                next.interval = 6;
            } else {
                next.interval = Math.round(
                    this.interval * next.easiness);
            }
        }
        return next;
    }
}
```

---

## 5. Common Pitfalls & Unit Test Cases

**Critical Tests:**
1. **EF Floor:** `schedule(q=0)` on card with EF=1.5 → EF=1.3, never below
2. **Again Resets Reps:** `reps=10, q=0` → `next.reps=0`
3. **Interval Rounding:** `interval=5, EF=2.3` → `round(5*2.3)=12`, not 11.5
4. **Easy Bonus:** `q=5` gives EF boost; ensure EF grows correctly
5. **Second Review:** `reps=1, q=3` → `interval=6` exactly
6. **Lapse Count:** Each q<3 increments; track for suspension logic
7. **Edge: First Review:** `reps=0, q=4` → `next.reps=1, next.interval=1`

**Common Bugs:**
- Forgetting `Math.round()` on interval (float truncation)
- Not enforcing EF floor after update
- Resetting interval but forgetting to reset reps on failure
- Off-by-one on repetition count logic

---

## Sources

- [SuperMemo SM-2 Algorithm (SuperMemo Archives)](https://www-beta.supermemo.com/archives1990-2015/english/ol/sm2)
- [How Spaced Repetition Actually Works: The SM-2 Algorithm (DEV Community)](https://dev.to/umangsinha12/how-spaced-repetition-actually-works-the-sm-2-algorithm-1ge3)
- [SM-2 Algorithm Explained (Tegaru Blog)](https://tegaru.app/en/blog/sm2-algorithm-explained)
- [Anki SM-2 Spaced Repetition Algorithm (RemNote Help)](https://help.remnote.com/en/articles/6026144-the-anki-sm-2-spaced-repetition-algorithm)
- [SM-2 Algorithm Pseudo-code (Anki Forums)](https://forums.ankiweb.net/t/sm-2-algorithm-pseudo-code/8350)
- [FSRS vs SM-2 Comparison (MemoForge Blog)](https://memoforge.app/blog/fsrs-vs-sm2-anki-algorithm-guide-2025/)

---

## Unresolved Questions

None. Algorithm is well-specified and stable. Implementation ready for `sm2-scheduler` service.
