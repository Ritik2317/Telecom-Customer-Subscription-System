package com.amdocs.telecom.security;

import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.exception.TSATMSException;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

/**
 * Turns a password into something safe to store, and checks a typed password
 * against what was stored.
 *
 * <p>A password is never written to the database. What is stored is a PBKDF2
 * derivation of it: the password is combined with a random per-account salt
 * and run through the hash function {@value AppConstants#PASSWORD_HASH_ITERATIONS}
 * times. The salt means two people who choose the same password still get
 * different stored values, and the iteration count means guessing is slow
 * even for an attacker holding the whole table.</p>
 *
 * <p>The stored hash carries the algorithm that produced it, as
 * {@code PBKDF2-SHA256:abc123...}. Recording it costs a few characters and
 * means a credential written by one build can still be verified by another
 * that resolved a different algorithm.</p>
 */
public final class PasswordHasher {

    private PasswordHasher() {
        throw new AssertionError("PasswordHasher is not instantiable");
    }

    /** Preferred derivation function, present in Java 8's SunJCE provider. */
    private static final String SHA256_ALGORITHM = "PBKDF2WithHmacSHA256";

    /** Guaranteed to exist on every Java 8 runtime, used only if the above is absent. */
    private static final String SHA1_ALGORITHM = "PBKDF2WithHmacSHA1";

    private static final String SHA256_TAG = "PBKDF2-SHA256";
    private static final String SHA1_TAG = "PBKDF2-SHA1";
    private static final char TAG_SEPARATOR = ':';

    private static final int KEY_LENGTH_BITS = 256;

    /**
     * Resolved once. A missing preferred algorithm is a property of the
     * runtime, so there is no point asking again on every login.
     */
    private static final String ACTIVE_ALGORITHM = resolveAlgorithm();

    /**
     * Seeded by the platform, and shared because {@link SecureRandom} is
     * thread safe and reseeding per call would be slower without being safer.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Derives a credential for a new or changed password, generating a fresh
     * salt for it.
     *
     * @param password the characters typed by the user; the caller keeps
     *                 ownership and should clear the array afterwards
     */
    public static HashedPassword hash(char[] password) {
        if (password == null || password.length == 0) {
            throw new TSATMSException(ErrorCode.VALIDATION_REQUIRED_FIELD,
                    "A password is required before it can be hashed");
        }
        byte[] salt = new byte[AppConstants.PASSWORD_SALT_BYTES];
        RANDOM.nextBytes(salt);

        String saltHex = toHex(salt);
        String hash = derive(password, salt, ACTIVE_ALGORITHM);
        return new HashedPassword(tagOf(ACTIVE_ALGORITHM) + TAG_SEPARATOR + hash, saltHex);
    }

    /**
     * Checks a typed password against a stored hash and salt.
     *
     * <p>Fails closed: a blank credential, an unreadable salt or a hash
     * written by an algorithm this build does not recognise all count as no
     * match rather than as an error the caller might overlook.</p>
     *
     * @return true only when the password reproduces the stored hash exactly
     */
    public static boolean matches(char[] password, String storedHash, String storedSalt) {
        if (password == null || password.length == 0 || storedHash == null || storedSalt == null) {
            return false;
        }
        String tag = algorithmTagOf(storedHash);
        String algorithm = algorithmForTag(tag);
        if (algorithm == null) {
            AppLogger.warn(PasswordHasher.class,
                    "Stored credential uses an unrecognised algorithm tag '" + tag + "'");
            return false;
        }

        byte[] salt;
        try {
            salt = fromHex(storedSalt);
        } catch (IllegalArgumentException malformed) {
            AppLogger.warn(PasswordHasher.class, "Stored salt is not valid hexadecimal");
            return false;
        }

        String expected = storedHash.substring(storedHash.indexOf(TAG_SEPARATOR) + 1);
        String actual = derive(password, salt, algorithm);

        // Compared as bytes through MessageDigest.isEqual, which does not
        // return early on the first difference. String.equals does, and the
        // time it takes leaks how much of a guess was right.
        try {
            return MessageDigest.isEqual(fromHex(expected), fromHex(actual));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * Whether a stored hash was produced by an algorithm this build still
     * prefers. Lets an administrator find credentials worth re-deriving after
     * the algorithm is upgraded.
     */
    public static boolean isCurrentAlgorithm(String storedHash) {
        return storedHash != null && tagOf(ACTIVE_ALGORITHM).equals(algorithmTagOf(storedHash));
    }

    /**
     * The algorithm label recorded in a stored hash, or {@code UNTAGGED} when
     * the value carries none, which is what the seeded sentinel looks like.
     */
    static String algorithmTagOf(String storedHash) {
        if (storedHash == null) {
            return "NONE";
        }
        int separator = storedHash.indexOf(TAG_SEPARATOR);
        return separator <= 0 ? "UNTAGGED" : storedHash.substring(0, separator);
    }

    /** The derivation function in use, for the startup diagnostics. */
    public static String activeAlgorithm() {
        return ACTIVE_ALGORITHM;
    }

    /* ---------- Internals ---------- */

    private static String derive(char[] password, byte[] salt, String algorithm) {
        PBEKeySpec specification = new PBEKeySpec(password, salt,
                AppConstants.PASSWORD_HASH_ITERATIONS, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(algorithm);
            return toHex(factory.generateSecret(specification).getEncoded());
        } catch (NoSuchAlgorithmException | InvalidKeySpecException cause) {
            throw new TSATMSException(ErrorCode.UNEXPECTED_ERROR,
                    "Password hashing failed using " + algorithm, cause);
        } finally {
            // Clears the copy PBEKeySpec made of the password.
            specification.clearPassword();
        }
    }

    private static String resolveAlgorithm() {
        try {
            SecretKeyFactory.getInstance(SHA256_ALGORITHM);
            return SHA256_ALGORITHM;
        } catch (NoSuchAlgorithmException absent) {
            AppLogger.warn(PasswordHasher.class, SHA256_ALGORITHM
                    + " is not available on this runtime, falling back to " + SHA1_ALGORITHM);
            return SHA1_ALGORITHM;
        }
    }

    private static String tagOf(String algorithm) {
        return SHA256_ALGORITHM.equals(algorithm) ? SHA256_TAG : SHA1_TAG;
    }

    /**
     * Maps a tag back to an algorithm through a fixed list, so a value read
     * from the database is never handed to the provider as-is.
     */
    private static String algorithmForTag(String tag) {
        if (SHA256_TAG.equals(tag)) {
            return SHA256_ALGORITHM;
        }
        if (SHA1_TAG.equals(tag)) {
            return SHA1_ALGORITHM;
        }
        return null;
    }

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int unsigned = bytes[index] & 0xFF;
            hex[index * 2] = HEX_DIGITS[unsigned >>> 4];
            hex[index * 2 + 1] = HEX_DIGITS[unsigned & 0x0F];
        }
        return new String(hex);
    }

    private static byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hexadecimal value must have an even length");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            int high = Character.digit(hex.charAt(index * 2), 16);
            int low = Character.digit(hex.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Value is not valid hexadecimal");
            }
            bytes[index] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}
