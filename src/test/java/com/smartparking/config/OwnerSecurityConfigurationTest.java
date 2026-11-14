package com.smartparking.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerSecurityConfigurationTest {
    private final OwnerSecurityConfiguration configuration = new OwnerSecurityConfiguration();

    @Test
    void ownerCredentialUsesOnlyTheConfiguredBcryptHash() {
        PasswordEncoder encoder = configuration.ownerPasswordEncoder();
        String hash = encoder.encode("owner-secret");
        UserDetailsService users = configuration.ownerUserDetailsService("owner", hash);

        assertEquals("owner", users.loadUserByUsername("owner").getUsername());
        assertEquals(hash, users.loadUserByUsername("owner").getPassword());
        assertTrue(encoder.matches("owner-secret", users.loadUserByUsername("owner").getPassword()));
    }

    @Test
    void ownerCredentialFailsClosedWhenTheHashIsMissing() {
        assertThrows(IllegalStateException.class,
                () -> configuration.ownerUserDetailsService("owner", ""));
    }
}
