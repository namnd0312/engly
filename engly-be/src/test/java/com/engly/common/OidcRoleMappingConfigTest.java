package com.engly.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the role → authority mapping. OPEN-GATE emits roles already in Spring form
 * (e.g. "ROLE_ADMIN"); the mapper must not double-prefix them, while still handling bare roles
 * and varied claim shapes.
 */
class OidcRoleMappingConfigTest {

    // --- toAuthority: the bug that hid the Admin UI ---

    @Test
    void toAuthority_alreadyPrefixed_isNotDoublePrefixed() {
        assertThat(OidcRoleMappingConfig.toAuthority("ROLE_ADMIN")).isEqualTo("ROLE_ADMIN");
        assertThat(OidcRoleMappingConfig.toAuthority("role_admin")).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void toAuthority_bareRole_getsRolePrefix() {
        assertThat(OidcRoleMappingConfig.toAuthority("admin")).isEqualTo("ROLE_ADMIN");
        assertThat(OidcRoleMappingConfig.toAuthority("FACTOR_PASSWORD")).isEqualTo("ROLE_FACTOR_PASSWORD");
    }

    // --- extractRoles: tolerant claim-shape parsing ---

    @Test
    void extractRoles_fromList_returnsAll() {
        assertThat(OidcRoleMappingConfig.extractRoles(List.of("ROLE_ADMIN", "ROLE_USER")))
                .containsExactly("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void extractRoles_fromDelimitedString_splits() {
        assertThat(OidcRoleMappingConfig.extractRoles("ROLE_ADMIN ROLE_USER,FACTOR_PASSWORD"))
                .containsExactly("ROLE_ADMIN", "ROLE_USER", "FACTOR_PASSWORD");
    }

    @Test
    void extractRoles_fromNullOrUnknownShape_isEmpty() {
        assertThat(OidcRoleMappingConfig.extractRoles(null)).isEmpty();
        assertThat(OidcRoleMappingConfig.extractRoles(42)).isEmpty();
        assertThat(OidcRoleMappingConfig.extractRoles("   ")).isEmpty();
    }
}
