package com.engly.vocab.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity mapping the vocab_word table.
 * Each word belongs to exactly one VocabDeck (ManyToOne, lazy-loaded).
 * Holds the English term, Vietnamese meaning, optional IPA/examples, and SRS verified flag.
 */
@Entity
@Table(name = "vocab_word")
public class VocabWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning side of the ManyToOne relationship to VocabDeck.
     * Lazy fetch avoids loading the full deck when only word data is needed.
     * FK column: deck_id (NOT NULL, ON DELETE CASCADE defined in V3 migration).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deck_id", nullable = false)
    private VocabDeck deck;

    @Column(name = "term", nullable = false, length = 120)
    private String term;

    @Column(name = "meaning_vi", nullable = false, length = 255)
    private String meaningVi;

    @Column(name = "ipa", length = 120)
    private String ipa;

    @Column(name = "example_en", length = 500)
    private String exampleEn;

    @Column(name = "example_vi", length = 500)
    private String exampleVi;

    @Column(name = "part_of_speech", length = 40)
    private String partOfSpeech;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    /** DB DEFAULT now() owns insertion; insertable=false prevents JPA from sending null. */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    public VocabWord() {}

    /**
     * Full-state mutator for admin update endpoint.
     * Called on a managed entity inside a @Transactional write.
     */
    public void update(String term, String meaningVi, String ipa,
                       String exampleEn, String exampleVi, String partOfSpeech) {
        this.term = term;
        this.meaningVi = meaningVi;
        this.ipa = ipa;
        this.exampleEn = exampleEn;
        this.exampleVi = exampleVi;
        this.partOfSpeech = partOfSpeech;
    }

    /** Targeted mutator for the verified-toggle endpoint. */
    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    /** Set the owning deck — called on create and when reassigning a word to another deck. */
    public void setDeck(VocabDeck deck) {
        this.deck = deck;
    }

    // Getters
    public Long getId()           { return id; }
    public VocabDeck getDeck()    { return deck; }
    public String getTerm()       { return term; }
    public String getMeaningVi()  { return meaningVi; }
    public String getIpa()        { return ipa; }
    public String getExampleEn()  { return exampleEn; }
    public String getExampleVi()  { return exampleVi; }
    public String getPartOfSpeech() { return partOfSpeech; }
    public boolean isVerified()   { return verified; }
    public Instant getCreatedAt() { return createdAt; }
}
