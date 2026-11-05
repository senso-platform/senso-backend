package dev.senso.core.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtKeyProviderTest {

    @Test
    void generatesTemporaryKeysWhenAllowedAndNotConfigured() {
        JwtKeyProvider provider = new JwtKeyProvider(new JwtProperties(null, " ", true));

        assertThat(provider.getPublicKey().getModulus())
                .isEqualTo(provider.getPrivateKey().getModulus());
    }

    @Test
    void failsFastWhenKeysAreMissingAndGenerationIsNotAllowed() {
        assertThatThrownBy(() -> new JwtKeyProvider(new JwtProperties(null, null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("JWT PUBLIC KEY is not configured");
    }

    @Test
    void doesNotGenerateWhenOnlyOneKeyIsConfigured() {
        assertThatThrownBy(() -> new JwtKeyProvider(new JwtProperties(
                        null, pem("PRIVATE KEY", keyPair().getPrivate().getEncoded()), true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loadsConfiguredKeysEvenWhenGenerationIsAllowed() throws Exception {
        KeyPair keyPair = keyPair();

        JwtKeyProvider provider = new JwtKeyProvider(new JwtProperties(
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                true));

        assertThat(provider.getPublicKey()).isEqualTo(keyPair.getPublic());
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(encoded) + "\n-----END " + type
                + "-----";
    }
}
