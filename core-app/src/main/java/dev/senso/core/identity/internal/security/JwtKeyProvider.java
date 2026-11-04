package dev.senso.core.identity.internal.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class JwtKeyProvider {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public JwtKeyProvider(JwtProperties properties) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            byte[] publicKeyBytes = decodePem(properties.publicKey(), "PUBLIC KEY");

            byte[] privateKeyBytes = decodePem(properties.privateKey(), "PRIVATE KEY");

            this.publicKey = (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            this.privateKey = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));

            if (!publicKey.getModulus().equals(privateKey.getModulus())) {
                throw new IllegalStateException("JWT public and private keys do not match");
            }

        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RSA keys from configuration", e);
        }
    }

    private static byte[] decodePem(String pem, String keyType) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("JWT " + keyType + " is not configured");
        }

        String normalized = pem.replace("-----BEGIN " + keyType + "-----", "")
                .replace("-----END " + keyType + "-----", "")
                .replaceAll("\\s+", "");

        return Base64.getDecoder().decode(normalized);
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public RSAPrivateKey getPrivateKey() {
        return privateKey;
    }
}
