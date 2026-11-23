package com.incidentops;

import com.fasterxml.jackson.databind.*;
import com.incidentops.catalog.*;
import com.incidentops.entity.*;
import com.incidentops.identity.team.*;
import com.incidentops.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired AuthSessionRepository sessions;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository members;
    @Autowired MonitoredServiceRepository services;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    private User admin;
    private User manager;
    private User responder;
    private User viewer;
    private User otherAdmin;
    private Team teamA;
    private Team teamB;
    private final Map<String, String> accessTokens = new HashMap<>();

    @BeforeEach
    void seed() {
        services.deleteAll();
        members.deleteAll();
        teams.deleteAll();
        users.deleteAll();
        accessTokens.clear();
        String hash = passwords.encode("Test-password-123!");
        admin = users.save(new User("admin@example.test", "Admin", hash, Role.ADMIN));
        manager = users.save(new User("manager@example.test", "Manager", hash, Role.INCIDENT_MANAGER));
        responder = users.save(new User("responder@example.test", "Responder", hash, Role.RESPONDER));
        viewer = users.save(new User("viewer@example.test", "Viewer", hash, Role.VIEWER));
        otherAdmin = users.save(new User("other@example.test", "Other Admin", hash, Role.ADMIN));
        users.flush();
        teamA = teams.save(new Team("Payments"));
        teamB = teams.save(new Team("Logistics"));
        teams.flush();
        members.saveAll(List.of(
            new TeamMember(teamA.getId(), admin.getId(), Role.ADMIN),
            new TeamMember(teamA.getId(), manager.getId(), Role.INCIDENT_MANAGER),
            new TeamMember(teamA.getId(), responder.getId(), Role.RESPONDER),
            new TeamMember(teamA.getId(), viewer.getId(), Role.VIEWER),
            new TeamMember(teamB.getId(), otherAdmin.getId(), Role.ADMIN)));
        members.flush();
    }

    @Test
    void foundationExposesHealthOpenApiMigrationAndCorrelationIds() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Correlation-ID", "acceptance-123"))
            .andExpect(status().isOk()).andExpect(header().string("X-Correlation-ID", "acceptance-123"));
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.info.title").value("IncidentOps AI API"));
        mvc.perform(post("/api/v1/teams/" + teamA.getId() + "/services")
            .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
            .content(body(Map.of("name", " ", "externalIdentifier", "not allowed!"))))
            .andExpect(status().isBadRequest())
            .andExpect(header().exists("X-Correlation-ID"))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.correlationId").isNotEmpty())
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists())
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'externalIdentifier')]").exists());
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true and version = '3'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void adminCanCreateTeamAndManageMembershipWhileViewerCannot() throws Exception {
        String response = mvc.perform(post("/api/v1/teams").header("Authorization", bearer(admin))
            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", "Observability"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("ADMIN"))
            .andReturn().getResponse().getContentAsString();
        String teamId = json.readTree(response).get("id").asText();
        assertThat(members.findByTeamIdAndUserId(teamId, admin.getId())).isPresent();

        mvc.perform(post("/api/v1/teams/" + teamA.getId() + "/members")
            .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
            .content(body(Map.of("userId", otherAdmin.getId(), "role", "RESPONDER"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("RESPONDER"));
        mvc.perform(post("/api/v1/teams/" + teamA.getId() + "/members")
            .header("Authorization", bearer(viewer)).contentType(MediaType.APPLICATION_JSON)
            .content(body(Map.of("userId", otherAdmin.getId(), "role", "ADMIN"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/teams").header("Authorization", bearer(viewer))
            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", "Forbidden Team"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void serviceNamesAreUniqueInsideTeamButNotGlobally() throws Exception {
        createService(admin, teamA, "Checkout API", null, "payments-checkout");
        mvc.perform(post("/api/v1/teams/" + teamA.getId() + "/services")
            .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
            .content(serviceBody("checkout api", null, "payments-checkout-2")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        createService(otherAdmin, teamB, "Checkout API", null, "logistics-checkout");
        assertThat(services.count()).isEqualTo(2);
    }

    @Test
    void catalogCrudPaginationAndDeterministicSortingWork() throws Exception {
        String zetaId = createService(admin, teamA, "Zeta API", responder.getId(), "zeta-api");
        String alphaId = createService(admin, teamA, "Alpha API", responder.getId(), "alpha-api");
        mvc.perform(get("/api/v1/teams/" + teamA.getId() + "/services?page=0&size=1")
            .header("Authorization", bearer(viewer)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(alphaId))
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/services/" + zetaId).header("Authorization", bearer(responder)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Zeta API"));
        mvc.perform(put("/api/v1/services/" + zetaId).header("Authorization", bearer(manager))
            .contentType(MediaType.APPLICATION_JSON)
            .content(serviceBody("Zeta Gateway", responder.getId(), "zeta-gateway")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Zeta Gateway"));
        mvc.perform(post("/api/v1/services/" + zetaId + "/deactivate")
            .header("Authorization", bearer(admin)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void responderAndViewerAreReadOnlyWhileManagerMayOperateServices() throws Exception {
        String id = createService(admin, teamA, "Identity API", responder.getId(), "identity-api");
        for (User readOnly : List.of(responder, viewer)) {
            mvc.perform(get("/api/v1/services/" + id).header("Authorization", bearer(readOnly)))
                .andExpect(status().isOk());
            mvc.perform(put("/api/v1/services/" + id).header("Authorization", bearer(readOnly))
                .contentType(MediaType.APPLICATION_JSON)
                .content(serviceBody("Changed", responder.getId(), "changed")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(post("/api/v1/teams/" + teamA.getId() + "/services")
            .header("Authorization", bearer(manager)).contentType(MediaType.APPLICATION_JSON)
            .content(serviceBody("Manager API", responder.getId(), "manager-api")))
            .andExpect(status().isCreated());
    }

    @Test
    void teamAMemberCannotDiscoverOrModifyTeamBDataByGuessingIds() throws Exception {
        String secret = createService(otherAdmin, teamB, "Secret Logistics", otherAdmin.getId(), "secret-logistics");
        mvc.perform(get("/api/v1/services/" + secret).header("Authorization", bearer(manager)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
        mvc.perform(put("/api/v1/services/" + secret).header("Authorization", bearer(manager))
            .contentType(MediaType.APPLICATION_JSON)
            .content(serviceBody("Guessed", manager.getId(), "guessed")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
        mvc.perform(post("/api/v1/services/" + secret + "/deactivate")
            .header("Authorization", bearer(admin)))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/teams/" + teamB.getId() + "/services")
            .header("Authorization", bearer(viewer)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
    }

    @Test
    void ownerExternalIdPaginationAndAuthenticationAreValidatedSafely() throws Exception {
        mvc.perform(post("/api/v1/teams/" + teamA.getId() + "/services")
            .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
            .content(serviceBody("Bad Owner", otherAdmin.getId(), "bad-owner")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OWNER"));
        mvc.perform(get("/api/v1/teams/" + teamA.getId() + "/services?size=101")
            .header("Authorization", bearer(admin)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/teams/" + teamA.getId() + "/services"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void everyMutationIsDeniedForReadOnlyMembersAndGlobalAdminHasNoTeamBypass() throws Exception {
        String id = createService(admin, teamA, "Protected API", null, "protected");
        for (User actor : List.of(viewer, responder)) {
            mvc.perform(post("/api/v1/teams/" + teamA.getId() + "/services")
                .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                .content(serviceBody("Forbidden API", null, "forbidden")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(post("/api/v1/services/" + id + "/deactivate").header("Authorization", bearer(actor)))
                .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/services/" + id).header("Authorization", bearer(otherAdmin)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
        assertThat(services.findById(id).orElseThrow().isActive()).isTrue();
        assertThat(services.count()).isEqualTo(1);
    }

    @Test
    void membershipRoleChangesAndRemovalApplyToAlreadyIssuedCredentials() throws Exception {
        String id = createService(admin, teamA, "Membership API", null, "membership");
        String credentials = bearer(manager);
        jdbc.update("update team_members set role = 'VIEWER' where team_id = ? and user_id = ?",
            teamA.getId(), manager.getId());
        mvc.perform(put("/api/v1/services/" + id).header("Authorization", credentials)
            .contentType(MediaType.APPLICATION_JSON).content(serviceBody("Cannot change", null, "cannot-change")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/services/" + id).header("Authorization", credentials)).andExpect(status().isOk());
        jdbc.update("delete from team_members where team_id = ? and user_id = ?", teamA.getId(), manager.getId());
        mvc.perform(get("/api/v1/services/" + id).header("Authorization", credentials))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
    }

    @Test
    void bodyCannotOverrideVerifiedTeamOrRoleAndGuessedIdsHaveSameError() throws Exception {
        String id = createService(otherAdmin, teamB, "Private API", null, "private");
        for (String guessed : List.of(id, UUID.randomUUID().toString())) {
            mvc.perform(get("/api/v1/services/" + guessed).header("Authorization", bearer(admin))
                .header("X-Correlation-ID", "scope-check"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Monitored service was not found"))
                .andExpect(jsonPath("$.correlationId").value("scope-check"));
        }
        mvc.perform(post("/api/v1/teams/" + teamA.getId() + "/services")
            .header("Authorization", bearer(viewer)).contentType(MediaType.APPLICATION_JSON)
            .content(body(Map.of("name", "Injected API", "teamId", teamB.getId(), "role", "ADMIN", "userId", admin.getId()))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(services.count()).isEqualTo(1);
    }

    @Test
    void trimmedNamesDescriptionLengthsAndPaginationFieldsAreValidated() throws Exception {
        for (String name : List.of(" a ", " Leading", "Trailing ")) {
            mvc.perform(post("/api/v1/teams/" + teamA.getId() + "/services")
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(serviceBody(name, null, "invalid")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists());
        }
        mvc.perform(post("/api/v1/teams").header("Authorization", bearer(admin))
            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", " a "))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/teams/" + teamA.getId() + "/services")
            .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
            .content(body(Map.of("name", "Long description", "description", "x".repeat(2001)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[?(@.field == 'description')]").exists());
        for (String query : List.of("size=0", "size=101", "page=-1", "page=10001")) {
            mvc.perform(get("/api/v1/teams/" + teamA.getId() + "/services?" + query)
                .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors").isNotEmpty());
        }
        assertThat(services.count()).isZero();
    }

    private String createService(User actor, Team team, String name, String ownerId, String externalId)
        throws Exception {
        String response = mvc.perform(post("/api/v1/teams/" + team.getId() + "/services")
            .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
            .content(serviceBody(name, ownerId, externalId)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.teamId").value(team.getId()))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }

    private String serviceBody(String name, String ownerId, String externalId) throws Exception {
        var value = new LinkedHashMap<String, Object>();
        value.put("name", name);
        value.put("description", "Production service owned by the team");
        if (ownerId != null) value.put("ownerUserId", ownerId);
        value.put("externalIdentifier", externalId);
        return body(value);
    }

    private String bearer(User user) {
        return "Bearer " + accessTokens.computeIfAbsent(user.getId(), ignored -> {
            Instant now = Instant.now();
            var session = sessions.saveAndFlush(new AuthSession(user.getId(), now.plusSeconds(3600)));
            var claims = JwtClaimsSet.builder().issuer("incident-ops").audience(List.of("incident-ops-api"))
                .subject(user.getId()).claim("sid", session.getId()).issuedAt(now).notBefore(now)
                .expiresAt(now.plusSeconds(900)).id(UUID.randomUUID().toString()).build();
            return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        });
    }

    private String body(Object value) throws Exception { return json.writeValueAsString(value); }
}
