# Verb Data Verification Checklist

## Seed Summary

| Field | Value |
|-------|-------|
| Source draft | `plans/260530-2323-engly-irregular-verbs-mvp-sso/reports/verb-data-draft.md` |
| Migration file | `backend/src/main/resources/db/migration/V2__seed-irregular-verbs-180-rows-ai-draft-unverified.sql` |
| Row count (DB confirmed) | **179** |
| verified flag | **false** (all rows — human verification pending) |
| IPA dialect | General American |
| Encoding | UTF-8 confirmed (IPA + Vietnamese diacritics render correctly) |
| Flyway status | V1 + V2 applied cleanly (2 migrations, schema v2) |

> **Note on count:** The draft file header states "180 verbs" but contains 179 actual data rows (the count inadvertently includes the column-header row). DB row count of 179 is correct and matches the actual data.

---

## Flagged-Uncertain Verbs (10 entries requiring priority human review)

These verbs have irregular or disputed forms, regional variants, or rare/archaic usage. A human reviewer should verify IPA transcription, Vietnamese meaning, and whether both alt forms are correct.

| # | base_form | Concern |
|---|-----------|---------|
| 1 | **dive** | AmE: `dived/dove` — `dove` as past_simple_alt (not past_participle_alt); BrE only uses `dived`. Confirm alt column placement. |
| 2 | **spit** | `spat` (BrE) vs `spit` (AmE) as past_simple; both listed. Confirm primary vs alt assignment. |
| 3 | **stink** | past_simple_alt=`stunk` (rare); past_participle_alt left NULL. Verify regional usage. |
| 4 | **shine** | Transitive (shone) vs intransitive (shined) split — context-dependent. Flag for meaning_vi clarification. |
| 5 | **hang** | `hung` (objects) vs `hanged` (executions) — `hanged` form omitted; consider adding note or alt. |
| 6 | **span** | Listed as irregular (spanned/spanned) but this is a regular verb in modern usage. Consider removing or reclassifying. |
| 7 | **smite** | Archaic; `smote/smitten` correct but highly formal. Verify meaning_vi `giáng đòn` is clear enough. |
| 8 | **slay** | `slew/slain` (formal/literary) vs `slayed` (informal, increasingly common). Alt form `slayed` not included. |
| 9 | **tread** | `trod/trodden` (BrE) or `trod/trod` (AmE); past_participle_alt=`trod` captured. Verify IPA for `trodden`. |
| 10 | **strive** | `strove/striven` (formal) vs `strived/strived` (informal, AmE); both captured as alts. Confirm primary preference. |

---

## Human Reviewer Checklist

Complete these steps before setting `verified = true` on any row:

### Preparation
- [ ] Read source draft: `plans/.../reports/verb-data-draft.md`
- [ ] Confirm DB has 179 rows: `SELECT count(*) FROM irregular_verb;`
- [ ] Confirm all rows are `verified = false`: `SELECT count(*) FROM irregular_verb WHERE verified = true;` → should be 0

### Per-Row Verification (179 rows)
- [ ] IPA transcriptions accurate (General American dialect, slash notation)
- [ ] Vietnamese meanings natural and commonly understood (not literal/archaic translations)
- [ ] `past_simple` / `past_participle` primary forms are the most common modern usage
- [ ] `*_alt` columns correctly capture secondary/regional forms (not swapped with primary)
- [ ] No single-quote escaping issues (apostrophes doubled in SQL — verify `it''s` style if any verb meanings contain apostrophes)

### Priority checks (flagged-uncertain verbs above)
- [ ] dive — alt column assignment correct
- [ ] spit — primary vs alt assignment correct
- [ ] stink — regional alt confirmed
- [ ] shine — meaning_vi sufficient
- [ ] hang — `hanged` omission acceptable for MVP
- [ ] span — confirm classification as irregular
- [ ] smite — meaning_vi clarity
- [ ] slay — `slayed` omission acceptable for MVP
- [ ] tread — IPA for `trodden` correct
- [ ] strive — primary form preference confirmed

### Post-Verification Steps
- [ ] For each verified row: `UPDATE irregular_verb SET verified = true WHERE base_form = '<verb>';`
- [ ] Or batch update once all verified: `UPDATE irregular_verb SET verified = true;`
- [ ] Create `V3__set-verified-true-all-irregular-verbs.sql` migration (do NOT edit V2)
- [ ] Re-run row count and spot-check after V3 applied
- [ ] Update this checklist: mark verification date and reviewer name

### Sign-off
- Verified by: _______________
- Date: _______________
- Notes: _______________
