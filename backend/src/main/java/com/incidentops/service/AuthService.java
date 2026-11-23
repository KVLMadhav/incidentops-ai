package com.incidentops.service;

import com.incidentops.dto.AuthDtos.*;
import com.incidentops.entity.*;
import com.incidentops.exception.ApiException;
import com.incidentops.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class AuthService {
    public static final long TOKEN_SECONDS = 900;
    private static final Duration SESSION_LIFETIME = Duration.ofDays(7);
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final String dummyHash;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, AuthSessionRepository sessions, RefreshTokenRepository tokens,
        PasswordEncoder passwords, JwtEncoder encoder, @Value("${app.jwt.issuer}") String issuer,
        @Value("${app.jwt.audience}") String audience) {
        this.users = users;
        this.sessions = sessions;
        this.tokens = tokens;
        this.passwords = passwords;
        this.encoder = encoder;
        this.issuer = issuer;
        this.audience = audience;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        var user = users.findByEmail(UserService.normalizeEmail(request.email()));
        boolean matches = passwords.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
        if (!matches || user.isEmpty() || !user.get().isEnabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
        var session = sessions.save(new AuthSession(user.get().getId(), Instant.now().plus(SESSION_LIFETIME)));
        return issue(session);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(RefreshRequest request) {
        String hash = hash(request.refreshToken());
        String sessionId = tokens.findSessionId(hash).orElseThrow(AuthService::invalidRefresh);
        var session = sessions.lockById(sessionId).orElseThrow(AuthService::invalidRefresh);
        var token = tokens.findById(hash).orElseThrow(AuthService::invalidRefresh);
        if (!session.isActive(Instant.now())) throw invalidRefresh();
        if (token.isConsumed()) {
            // Commit this revocation even though the HTTP response will be 401.
            session.revoke();
            throw invalidRefresh();
        }
        if (users.findById(session.getUserId()).filter(User::isEnabled).isEmpty()) {
            session.revoke();
            throw invalidRefresh();
        }
        token.consume();
        return issue(session);
    }

    @Transactional
    public void logout(RefreshRequest request) {
        tokens.findSessionId(hash(request.refreshToken())).flatMap(sessions::lockById)
            .ifPresent(AuthSession::revoke);
    }

    private TokenResponse issue(AuthSession session) {
        Instant now = Instant.now();
        Instant accessExpiry = now.plusSeconds(TOKEN_SECONDS).isBefore(session.getExpiresAt())
            ? now.plusSeconds(TOKEN_SECONDS) : session.getExpiresAt();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
            .subject(session.getUserId()).claim("sid", session.getId()).issuedAt(now).notBefore(now)
            .expiresAt(accessExpiry).id(UUID.randomUUID().toString()).build();
        String access = encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new RefreshToken(hash(refresh), session.getId()));
        return new TokenResponse(access, "Bearer", Duration.between(now, accessExpiry).toSeconds(),
            refresh, Math.max(0, Duration.between(now, session.getExpiresAt()).toSeconds()));
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Refresh token is invalid or expired");
    }
}
