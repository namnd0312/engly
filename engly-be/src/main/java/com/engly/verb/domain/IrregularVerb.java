package com.engly.verb.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity mapping the irregular_verb table (seeded in phase 02).
 * Columns: id, base_form, past_simple, past_participle,
 *          past_simple_alt, past_participle_alt,
 *          ipa_base, ipa_past, ipa_pp,
 *          meaning_vi, verified, created_at
 */
@Entity
@Table(name = "irregular_verb")
public class IrregularVerb {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "base_form", nullable = false, unique = true, length = 100)
    private String baseForm;

    @Column(name = "past_simple", nullable = false, length = 100)
    private String pastSimple;

    @Column(name = "past_participle", nullable = false, length = 100)
    private String pastParticiple;

    @Column(name = "past_simple_alt", length = 100)
    private String pastSimpleAlt;

    @Column(name = "past_participle_alt", length = 100)
    private String pastParticipleAlt;

    @Column(name = "ipa_base", length = 100)
    private String ipaBase;

    @Column(name = "ipa_past", length = 100)
    private String ipaPast;

    @Column(name = "ipa_pp", length = 100)
    private String ipaPp;

    @Column(name = "meaning_vi", length = 255)
    private String meaningVi;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    // L1: insertable=false lets DB DEFAULT now() handle inserts; JPA never sends null
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    // Default constructor required by JPA
    public IrregularVerb() {}

    // Getters
    public Long getId() { return id; }
    public String getBaseForm() { return baseForm; }
    public String getPastSimple() { return pastSimple; }
    public String getPastParticiple() { return pastParticiple; }
    public String getPastSimpleAlt() { return pastSimpleAlt; }
    public String getPastParticipleAlt() { return pastParticipleAlt; }
    public String getIpaBase() { return ipaBase; }
    public String getIpaPast() { return ipaPast; }
    public String getIpaPp() { return ipaPp; }
    public String getMeaningVi() { return meaningVi; }
    public boolean isVerified() { return verified; }
    public Instant getCreatedAt() { return createdAt; }
}
