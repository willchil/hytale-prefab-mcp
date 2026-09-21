package games.crescentnetwork.mcp.auth;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Issues and checks the personal token a client sends to prove which player it is acting for.
 *
 * <p>A token is derived, not stored: it is the player's id alongside an HMAC of that id under the
 * server's secret. Nothing has to be persisted per player, a token is the same every time it is
 * shown, and changing the secret in the config invalidates every token at once.
 *
 * <p>The player id travels in the token so a token can be checked on its own. Without it the server
 * would have to try every id it has ever seen to find out who was calling, which does not work for a
 * player who has not joined yet and gets slower as a server grows.
 */
public final class McpTokens {

    /** Separates the player id from its signature. Not valid in either half, so parsing is unambiguous. */
    private static final char SEPARATOR = '.';

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Bytes of HMAC kept in the token. 24 bytes is 192 bits, far past what a forger could search,
     * and keeps the token short enough to read back over chat.
     */
    private static final int SIGNATURE_BYTES = 24;

    /** Bytes of entropy in a generated secret. */
    private static final int SECRET_BYTES = 32;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    public McpTokens(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** A fresh secret for a config that has none yet. */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** The token for a player. Deterministic, so the same player is always shown the same one. */
    public String issue(UUID player) {
        String subject = player.toString();
        return subject + SEPARATOR + sign(subject);
    }

    /**
     * Checks a token and returns who it belongs to.
     *
     * @return the player the token was issued for, or null if it is malformed or does not verify
     */
    @Nullable
    public UUID verify(@Nullable String token) {
        if (token == null) return null;
        int split = token.lastIndexOf(SEPARATOR);
        if (split <= 0 || split == token.length() - 1) return null;

        String subject = token.substring(0, split);
        String presented = token.substring(split + 1);

        byte[] expected = sign(subject).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = presented.getBytes(StandardCharsets.US_ASCII);
        // Constant-time: a length-dependent or short-circuiting compare leaks how much of a guess
        // was right, which is enough to recover a signature one character at a time.
        if (!MessageDigest.isEqual(expected, actual)) return null;

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String sign(String subject) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] full = mac.doFinal(subject.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[SIGNATURE_BYTES];
            System.arraycopy(full, 0, truncated, 0, SIGNATURE_BYTES);
            return ENCODER.encodeToString(truncated);
        } catch (Exception e) {
            // HmacSHA256 is required of every JVM, so this cannot happen in practice; failing loudly
            // beats returning something that would silently authenticate nobody.
            throw new IllegalStateException("Could not sign an MCP token", e);
        }
    }

    /** Pulls the token out of an {@code Authorization: Bearer <token>} header. */
    @Nullable
    public static String fromAuthorizationHeader(@Nullable String header) {
        if (header == null) return null;
        String trimmed = header.trim();
        if (trimmed.length() < 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /** Decodes a secret, only to confirm it is usable; the value itself is kept as text. */
    public static boolean isUsableSecret(@Nullable String secret) {
        if (secret == null || secret.length() < 16) return false;
        try {
            DECODER.decode(secret);
            return true;
        } catch (IllegalArgumentException e) {
            // Not base64, but any sufficiently long text works as key material.
            return true;
        }
    }
}
