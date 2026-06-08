package com.engly.vocab.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity mapping the vocab_deck table.
 * A deck groups related vocabulary words under a named topic (e.g. Travel, Business).
 * Slug is immutable after creation — used for stable URL paths and seed FK resolution.
 */
@Entity
@Table(name = "vocab_deck")
public class VocabDeck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** URL-safe unique identifier; set at creation, never changed. */
    @Column(name = "slug", nullable = false, unique = true, length = 140)
    private String slug;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "topic", length = 80)
    private String topic;

    /** DB DEFAULT now() owns insertion; insertable=false prevents JPA from sending null. */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    public VocabDeck() {}

    /**
     * Full-state mutator for admin update endpoint (slug immutable — excluded).
     * Called on a managed entity inside a @Transactional write.
     */
    public void update(String name, String description, String topic) {
        this.name = name;
        this.description = description;
        this.topic = topic;
    }

    /** Set slug at creation time only — never call after entity is persisted. */
    public void setSlug(String slug) { this.slug = slug; }

    // Getters
    public Long getId()          { return id; }
    public String getName()      { return name; }
    public String getSlug()      { return slug; }
    public String getDescription() { return description; }
    public String getTopic()     { return topic; }
    public Instant getCreatedAt() { return createdAt; }
}
