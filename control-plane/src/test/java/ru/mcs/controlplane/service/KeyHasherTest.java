package ru.mcs.controlplane.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyHasherTest {

    @Test
    void sha256IsStable() {
        var hash = KeyHasher.sha256("secret");
        assertEquals(64, hash.length());
        assertEquals(hash, KeyHasher.sha256("secret"));
    }

    @Test
    void apiKeyHasExpectedFormat() {
        var key = KeyHasher.generateApiKey();
        assertTrue(key.startsWith("ak_"));
        assertEquals("ak_".length() + 48, key.length());
    }

    @Test
    void accessCodeHasExpectedFormat() {
        var code = KeyHasher.generateAccessCode();
        assertTrue(code.startsWith("SUB-"));
        assertEquals("SUB-XXXX-XXXX-XXXX".length(), code.length());
    }
}
