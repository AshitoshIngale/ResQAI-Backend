package com.resqai;

import com.resqai.security.Argon2idPasswordEncoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Argon2idPasswordEncoderTest {

    private final Argon2idPasswordEncoder encoder = new Argon2idPasswordEncoder();

    @Test
    void encodesInArgon2idPhcFormat() {
        String hash = encoder.encode("S3cure!Passw0rd");
        assertTrue(hash.startsWith("$argon2id$v=19$m="), "hash: " + hash);
        assertTrue(hash.contains("t=2,p=1"));
    }

    @Test
    void matchesCorrectPasswordAndRejectsWrongPassword() {
        String hash = encoder.encode("S3cure!Passw0rd");
        assertTrue(encoder.matches("S3cure!Passw0rd", hash));
        assertFalse(encoder.matches("wrong-password", hash));
        assertFalse(encoder.matches("S3cure!Passw0rd ", hash));
        assertFalse(encoder.matches("", hash));
    }

    @Test
    void saltsAreRandomSoHashesDiffer() {
        String a = encoder.encode("same-password");
        String b = encoder.encode("same-password");
        assertNotEquals(a, b);
        assertTrue(encoder.matches("same-password", a));
        assertTrue(encoder.matches("same-password", b));
    }

    @Test
    void malformedHashFailsClosed() {
        assertFalse(encoder.matches("x", "not-a-hash"));
        assertFalse(encoder.matches("x", null));
        assertFalse(encoder.matches(null, "$argon2id$v=19$m=19456,t=2,p=1$abc$def"));
    }
}
