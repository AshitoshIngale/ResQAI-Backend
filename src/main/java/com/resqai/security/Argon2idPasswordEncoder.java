package com.resqai.security;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Argon2id password hashing (OWASP-recommended parameters, PHC string format).
 * Hashes are self-contained (salt + parameters embedded) so parameters can be
 * raised later without breaking existing hashes.
 */
@Component
public class Argon2idPasswordEncoder {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    // OWASP 2024 baseline: m=19456 KiB, t=2, p=1 (adjust per server capacity)
    private static final int MEMORY_KIB = 19456;
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;

    private final SecureRandom random = new SecureRandom();

    public String encode(String rawPassword) {
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        byte[] hash = argon2id(rawPassword.toCharArray(), salt);
        return String.format("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
            Argon2Parameters.ARGON2_VERSION_13,
            MEMORY_KIB, ITERATIONS, PARALLELISM,
            Base64.getEncoder().withoutPadding().encodeToString(salt),
            Base64.getEncoder().withoutPadding().encodeToString(hash));
    }

    public boolean matches(String rawPassword, String encoded) {
        if (rawPassword == null || encoded == null) return false;
        try {
            String[] parts = encoded.split("\\$");
            // parts: "", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash
            if (parts.length < 6 || !"argon2id".equals(parts[1])) return false;
            int memory = 0, iterations = 0, parallelism = 1;
            for (String param : parts[3].split(",")) {
                if (param.startsWith("m=")) memory = Integer.parseInt(param.substring(2));
                else if (param.startsWith("t=")) iterations = Integer.parseInt(param.substring(2));
                else if (param.startsWith("p=")) parallelism = Integer.parseInt(param.substring(2));
            }
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);

            Argon2Parameters.Builder builder = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(memory)
                .withIterations(iterations)
                .withParallelism(parallelism);
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(builder.build());
            byte[] actual = new byte[expected.length];
            generator.generateBytes(rawPassword.toCharArray(), actual);

            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] argon2id(char[] password, byte[] salt) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(MEMORY_KIB)
            .withIterations(ITERATIONS)
            .withParallelism(PARALLELISM)
            .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] hash = new byte[HASH_LENGTH];
        generator.generateBytes(password, hash);
        return hash;
    }

    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }
}
