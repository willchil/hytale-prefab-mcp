package games.crescentnetwork.mcp;

import games.crescentnetwork.mcp.auth.McpTokens;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers token derivation and, more importantly, every way a bad token must fail to verify. */
class TokenTest {

    private static final String SECRET = "test-secret-that-is-long-enough-to-use";
    private static final UUID ALICE = UUID.fromString("657827e9-8e16-4614-b533-4cd444373a46");
    private static final UUID BOB = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final McpTokens tokens = new McpTokens(SECRET);

    @Test
    void aTokenVerifiesBackToThePlayerItWasIssuedFor() {
        assertEquals(ALICE, tokens.verify(tokens.issue(ALICE)));
    }

    @Test
    void tokensAreStableSoTheCommandCanShowTheSameOneAgain() {
        assertEquals(tokens.issue(ALICE), tokens.issue(ALICE));
    }

    @Test
    void differentPlayersGetDifferentTokens() {
        assertNotEquals(tokens.issue(ALICE), tokens.issue(BOB));
    }

    @Test
    void aTokenFromAnotherServerDoesNotVerify() {
        // The point of the per-server secret: a token minted elsewhere is worthless here.
        String foreign = new McpTokens("a-completely-different-server-secret").issue(ALICE);
        assertNull(tokens.verify(foreign));
    }

    @Test
    void rotatingTheSecretInvalidatesEveryToken() {
        String before = tokens.issue(ALICE);
        assertNull(new McpTokens("rotated-secret-value-long-enough").verify(before));
    }

    @Test
    void aTokenCannotBeRepointedAtAnotherPlayer() {
        // Swapping the subject while keeping a valid signature is the obvious forgery to try.
        String alice = tokens.issue(ALICE);
        String signature = alice.substring(alice.lastIndexOf('.') + 1);
        assertNull(tokens.verify(BOB + "." + signature));
    }

    @Test
    void aTamperedSignatureDoesNotVerify() {
        String token = tokens.issue(ALICE);
        int split = token.lastIndexOf('.');
        String signature = token.substring(split + 1);
        // Flip one character of the signature; everything else stays byte-identical.
        char first = signature.charAt(0);
        String tampered = (first == 'A' ? 'B' : 'A') + signature.substring(1);
        assertNull(tokens.verify(token.substring(0, split + 1) + tampered));
    }

    @Test
    void malformedTokensAreRejectedRatherThanThrowing() {
        for (String bad : new String[]{
            null, "", ".", "no-separator", "not-a-uuid.AAAA", ALICE.toString(), ALICE + ".", "." + ALICE
        }) {
            assertNull(tokens.verify(bad), "should not verify: " + bad);
        }
    }

    @Test
    void bearerHeaderParsing() {
        String token = tokens.issue(ALICE);
        assertEquals(token, McpTokens.fromAuthorizationHeader("Bearer " + token));
        // Header names and schemes are case-insensitive per HTTP, and clients differ.
        assertEquals(token, McpTokens.fromAuthorizationHeader("bearer " + token));
        assertEquals(token, McpTokens.fromAuthorizationHeader("  Bearer   " + token + "  "));

        assertNull(McpTokens.fromAuthorizationHeader(null));
        assertNull(McpTokens.fromAuthorizationHeader(""));
        assertNull(McpTokens.fromAuthorizationHeader("Bearer"));
        assertNull(McpTokens.fromAuthorizationHeader("Bearer "));
        assertNull(McpTokens.fromAuthorizationHeader(token));
        assertNull(McpTokens.fromAuthorizationHeader("Basic " + token));
    }

    @Test
    void generatedSecretsAreUsableAndDistinct() {
        String first = McpTokens.generateSecret();
        String second = McpTokens.generateSecret();
        assertNotEquals(first, second);
        assertTrue(McpTokens.isUsableSecret(first));
        assertFalse(McpTokens.isUsableSecret("short"));
        assertFalse(McpTokens.isUsableSecret(null));
    }

    @Test
    void theTokenCarriesTheSubjectSoItCanBeCheckedAlone() {
        // Verification must not need a list of known players; the subject travels with the token.
        String token = tokens.issue(ALICE);
        assertTrue(token.startsWith(ALICE.toString() + "."));
    }
}
