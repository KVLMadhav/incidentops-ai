package com.incidentops;

import com.fasterxml.jackson.databind.*;
import com.incidentops.entity.*;
import com.incidentops.repository.*;
import com.incidentops.service.AuthService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class RefreshIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    static final String PASSWORD = "Test-password-123!";
    @BeforeEach void seed() {
        users.deleteAll();
        users.saveAndFlush(new User("viewer@example.test", "Viewer", passwords.encode(PASSWORD), Role.VIEWER));
    }
    String body(Object value) throws Exception { return json.writeValueAsString(value); }
    JsonNode login() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content(body(Map.of("email","viewer@example.test","password",PASSWORD))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    String payload(String token) throws Exception { return body(Map.of("refreshToken",token)); }
    JsonNode rotate(String token) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/refresh").contentType("application/json")
            .content(payload(token))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andReturn().getResponse().getContentAsString());
    }
    void rejectedRefresh(String token) throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(payload(token)))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH"));
    }
    void rejectedAccess(String token) throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+token))
            .andExpect(status().isUnauthorized());
    }
    @Test void publicRegistrationAlwaysCreatesViewerAndRejectsRoleInjection() throws Exception {
        var request = new HashMap<String,String>(Map.of("email","new@example.test","displayName","New","password",PASSWORD));
        request.put("role","ADMIN");
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body(request)))
            .andExpect(status().isBadRequest());
        assertThat(users.count()).isEqualTo(1);
        request.remove("role");
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body(request)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("VIEWER"))
            .andExpect(jsonPath("$.passwordHash").doesNotExist());
        request.put("email","NEW@example.test");
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body(request)))
            .andExpect(status().isConflict());
    }
    @Test void rotationStoresOnlyHashesAndKeepsAbsoluteSessionExpiry() throws Exception {
        var original = login();
        String raw = original.get("refreshToken").asText();
        String hash = jdbc.queryForObject("select token_hash from refresh_tokens", String.class);
        assertThat(hash).isEqualTo(AuthService.hash(raw)).isNotEqualTo(raw);
        var rotated = rotate(raw);
        assertThat(rotated.get("refreshToken").asText()).isNotEqualTo(raw);
        assertThat(rotated.get("refreshExpiresIn").asLong()).isLessThanOrEqualTo(original.get("refreshExpiresIn").asLong());
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions",Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+rotated.get("accessToken").asText()))
            .andExpect(status().isOk());
    }
    @Test void reuseRevokesDescendantTokensAndAccessButNotOtherLogin() throws Exception {
        var first = login();
        var independent = login();
        var next = rotate(first.get("refreshToken").asText());
        rejectedRefresh(first.get("refreshToken").asText());
        rejectedRefresh(next.get("refreshToken").asText());
        rejectedAccess(next.get("accessToken").asText());
        rejectedAccess(first.get("accessToken").asText());
        rotate(independent.get("refreshToken").asText());
    }
    @Test void logoutIsIdempotentAndRevokesAccessAndRefresh() throws Exception {
        var session = login();
        for (int i=0;i<2;i++) mvc.perform(post("/api/v1/auth/logout").contentType("application/json")
            .content(payload(session.get("refreshToken").asText()))).andExpect(status().isNoContent());
        rejectedRefresh(session.get("refreshToken").asText());
        rejectedAccess(session.get("accessToken").asText());
    }
    @Test void expiredOrDisabledSessionCannotRefresh() throws Exception {
        var session = login();
        jdbc.update("update auth_sessions set expires_at = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5)));
        rejectedRefresh(session.get("refreshToken").asText());
        rejectedAccess(session.get("accessToken").asText());
        session = login();
        jdbc.update("update users set enabled = false");
        rejectedRefresh(session.get("refreshToken").asText());
    }
    @Test void unknownAndMalformedRefreshTokensAreRejected() throws Exception {
        rejectedRefresh("a".repeat(43));
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(payload("short")))
            .andExpect(status().isBadRequest());
    }
    @Test void concurrentRefreshCannotCreateTwoUsableDescendants() throws Exception {
        var original = login();
        String request = payload(original.get("refreshToken").asText());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> action = () -> {
                start.await();
                return mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(request))
                    .andReturn().getResponse().getStatus();
            };
            var one = executor.submit(action);
            var two = executor.submit(action);
            start.countDown();
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200,401);
        }
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions where revoked = true",Integer.class)).isEqualTo(1);
        rejectedAccess(original.get("accessToken").asText());
    }
    @Test void allFourRolesHaveCorrectUserAdministrationBoundary() throws Exception {
        for (Role role : Role.values()) {
            jdbc.update("update users set role = ?",role.name());
            var session = login();
            mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+session.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(role.name()));
            mvc.perform(get("/api/v1/users").header("Authorization","Bearer "+session.get("accessToken").asText()))
                .andExpect(status().is(role == Role.ADMIN ? 200 : 403));
        }
    }
}
