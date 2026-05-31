-- V1: Create irregular_verb table
-- Stores English irregular verbs with IPA transcriptions and Vietnamese meanings.
-- Alternate past/pp forms supported via nullable _alt columns (KISS, explicit, searchable).

CREATE TABLE IF NOT EXISTS irregular_verb (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    base_form        varchar(64)  NOT NULL,
    past_simple      varchar(64)  NOT NULL,
    past_participle  varchar(64)  NOT NULL,
    past_simple_alt  varchar(64)  NULL,
    past_participle_alt varchar(64) NULL,
    ipa_base         varchar(96)  NULL,
    ipa_past         varchar(96)  NULL,
    ipa_pp           varchar(96)  NULL,
    meaning_vi       varchar(255) NOT NULL,
    verified         boolean      NOT NULL DEFAULT false,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_irregular_verb_base_form UNIQUE (base_form)
);

-- Case-insensitive lookup on base_form (search/autocomplete)
CREATE INDEX idx_verb_lower_base ON irregular_verb (lower(base_form));

-- Case-insensitive lookup on Vietnamese meaning (search by meaning)
CREATE INDEX idx_verb_lower_meaning ON irregular_verb (lower(meaning_vi));
