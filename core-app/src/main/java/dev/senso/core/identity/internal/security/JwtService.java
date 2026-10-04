package dev.senso.core.identity.internal.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import dev.senso.core.identity.internal.domain.User;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final Clock clock;

    public JwtService(JwtKeyProvider keyProvider, Clock clock) {

        RSAKey rsaKey = new RSAKey.Builder(keyProvider.getPublicKey())
            .privateKey(keyProvider.getPrivateKey())
            .build();

        this.jwtEncoder = new NimbusJwtEncoder(
            new ImmutableJWKSet<SecurityContext>(
                new JWKSet(rsaKey)
            )
        );

        this.clock = clock;
    }

    public String createAccessToken(User user) {

        Instant now = clock.instant();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiresAt(now.plus(15, ChronoUnit.MINUTES))
            .claim("roles", user.getRoles())
            .build();

        return jwtEncoder
            .encode(JwtEncoderParameters.from(claims))
            .getTokenValue();
    }
}
