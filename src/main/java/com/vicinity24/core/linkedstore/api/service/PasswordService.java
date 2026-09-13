package com.vicinity24.core.linkedstore.api.service;

import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

@Service
public class PasswordService {

    private static final int SALT_BYTES = 32;
    private static final int HASH_ITERATIONS = 310_000;
    private static final int HASH_BYTES = 64;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA512";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public String generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        return Base64.getEncoder().withoutPadding().encodeToString(salt);
    }

    public String hash(String plaintext, String salt) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException("Salt cannot be empty");
        }
        try {
            byte[] saltBytes = Base64.getDecoder().decode(padBase64(salt));
            PBEKeySpec spec = new PBEKeySpec(
                    plaintext.toCharArray(),
                    saltBytes,
                    HASH_ITERATIONS,
                    HASH_BYTES * 8
            );
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return HASH_ITERATIONS + "$" + salt + "$"
                    + Base64.getEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to hash password", e);
        }
    }

    public boolean verify(String plaintext, String storedHash, String storedSalt) {
        if (plaintext == null || storedHash == null || storedSalt == null) return false;
        if (plaintext.isBlank() || storedHash.isBlank() || storedSalt.isBlank()) return false;
        String recomputed;
        try {
            recomputed = hash(plaintext, storedSalt);
        } catch (Exception ex) {
            return false;
        }
        return constantTimeEquals(recomputed, storedHash);
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes();
        byte[] bBytes = b.getBytes();
        if (aBytes.length != bBytes.length) return false;
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }

    private static String padBase64(String src) {
        int mod = src.length() % 4;
        if (mod == 0) return src;
        return src + "=".repeat(4 - mod);
    }
}
