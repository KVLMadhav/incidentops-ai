package com.incidentops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentops.entity.*;
import com.incidentops.repository.*;
import com.incidentops.service.*;
import com.incidentops.dto.*;
import com.incidentops.repository.AuthSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthSessionRepository sessions;
    private User admin;
    private User viewer;
    private static final String PASSWORD = "Test-password-123!";

    @BeforeEach
    void seed() {
        users.deleteAll();
        String hash = passwords.encode(PASSWORD);
        admin = users.saveAndFlush(new User("admin@example.test", "Admin", hash, Role.ADMIN));
        viewer = users.saveAndFlush(new User("viewer@example.test", "Viewer", hash, Role.VIEWER));
    }

    private String body(Object value) throws Exception { return json.writeValueAsString(value); }

    private String login(String email) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content(body(Map.of("email", email, "password", PASSWORD))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("accessToken").asText();
    }

    private String signed(String subject, String issuer, String audience, Instant expiry) {
        var session = sessions.saveAndFlush(new AuthSession(subject, Instant.now().plusSeconds(3600)));
        var claims = JwtClaimsSet.builder().subject(subject).issuer(issuer)
            .claim("sid", session.getId())
            .audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(1000))
            .expiresAt(expiry).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    }

    @Test void loginAndMeReturnSafeUserData() throws Exception {
        String token = login("ADMIN@example.test");
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(admin.getId()))
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.password").doesNotExist());
        assertThat(admin.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, admin.getPasswordHash())).isTrue();
    }

    @Test void wrongAndUnknownCredentialsReturnSameGenericError() throws Exception {
        for (String email : List.of("admin@example.test", "unknown@example.test")) {
            mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(body(Map.of("email", email, "password", "Wrong-password-123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        }
    }

    @Test void missingAndMalformedTokensAreUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid.token.value"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test void viewerCannotListUsersOrCreateAdmin() throws Exception {
        String token = login(viewer.getEmail());
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + token)
            .contentType("application/json").content(body(Map.of("email", "evil@example.test",
                "displayName", "Evil", "password", PASSWORD, "role", "ADMIN"))))
            .andExpect(status().isForbidden());
        assertThat(users.count()).isEqualTo(2);
    }

    @Test void adminCreatesUserAndDuplicateEmailConflicts() throws Exception {
        String token = login(admin.getEmail());
        String payload = body(Map.of("email", "NEW@example.test", "displayName", "New Responder",
            "password", PASSWORD, "role", "RESPONDER"));
        mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + token)
            .contentType("application/json").content(payload))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("new@example.test"))
            .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + token)
            .contentType("application/json").content(payload))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        login("new@example.test");
    }

    @Test void validationRejectsWeakPasswordsUnknownRolesAndExtraFields() throws Exception {
        String token = login(admin.getEmail());
        for (var payload : List.of(
            Map.of("email", "new@example.test", "displayName", "New", "password", "short", "role", "VIEWER"),
            Map.of("email", "bad-email", "displayName", "New", "password", PASSWORD, "role", "VIEWER"),
            Map.of("email", "new@example.test", "displayName", "New", "password", PASSWORD, "role", "SUPERUSER"),
            Map.of("email", "new@example.test", "displayName", "New", "password", PASSWORD, "role", "VIEWER", "enabled", "true"))) {
            mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(body(payload)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test void paginationIsBoundedAndStable() throws Exception {
        String token = login(admin.getEmail());
        mvc.perform(get("/api/v1/users?size=1&page=0").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.totalElements").value(2));
        for (String query : List.of("size=101", "size=0", "page=-1", "page=10001", "page=nope")) {
            mvc.perform(get("/api/v1/users?" + query).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        }
    }

    @Test void expiredWrongIssuerAndWrongAudienceTokensAreRejected() throws Exception {
        String valid = signed(admin.getId(), "incident-ops", "incident-ops-api", Instant.now().plusSeconds(900));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + valid))
            .andExpect(status().isOk());
        for (String token : List.of(
            signed(admin.getId(), "incident-ops", "incident-ops-api", Instant.now().minusSeconds(120)),
            signed(admin.getId(), "attacker", "incident-ops-api", Instant.now().plusSeconds(900)),
            signed(admin.getId(), "incident-ops", "other-api", Instant.now().plusSeconds(900)))) {
            mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test void forgedSignatureIsRejected() throws Exception {
        String token = login(admin.getEmail());
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." +
            (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + forged))
            .andExpect(status().isUnauthorized());
    }

    @Test void disabledAccountCannotLoginOrReuseAnExistingToken() throws Exception {
        String token = login(admin.getEmail());
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", admin.getId());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content(body(Map.of("email", admin.getEmail(), "password", PASSWORD))))
            .andExpect(status().isUnauthorized());
    }

    @Test void roleChangeTakesEffectForExistingToken() throws Exception {
        String token = login(admin.getEmail());
        jdbc.update("UPDATE users SET role = 'VIEWER' WHERE id = ?", admin.getId());
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    @Test void deletedAccountTokenIsRejected() throws Exception {
        String token = login(viewer.getEmail());
        users.deleteById(viewer.getId());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test void invalidSignupIsRejectedAndUnmatchedEndpointsAreDenied() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/actuator/env").header("Authorization", "Bearer " + login(admin.getEmail())))
            .andExpect(status().isForbidden());
    }

    @Test void corsAllowsOnlyConfiguredBrowserOrigin() throws Exception {
        mvc.perform(options("/api/v1/users/me").header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/v1/users/me").header("Origin", "https://evil.example")
            .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden());
    }

    @Test void healthIsPublicWithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version = '1'", Long.class))
            .isEqualTo(1);
    }
}
