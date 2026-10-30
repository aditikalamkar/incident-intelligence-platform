package com.opspilot.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.opspilot.Concurrently;
import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestFixtures.TestTenant;
import com.opspilot.TestFixtures.TestUser;
import com.opspilot.common.Hashing;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class SessionApiTest {

    private static final Pattern COOKIE_VALUE = Pattern.compile(SessionCookie.NAME + "=([^;]*)");
    private static final Pattern MAX_AGE = Pattern.compile("Max-Age=(\\d+)");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SessionService sessions;

    TestTenant tenant;
    TestUser user;

    @BeforeEach
    void setUp() {
        tenant = fixtures.tenant();
        user = fixtures.user(tenant.tenantId(), Role.RESPONDER);
    }

    @Test
    void signingInSetsASessionCookieThatScriptsAndOtherSitesCannotUse() throws Exception {
        String setCookie = mvc.perform(login(user.email()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

        assertThat(setCookie)
                .startsWith(SessionCookie.NAME + "=")
                .contains("Path=/api/v1/auth", "HttpOnly", "Secure", "SameSite=Strict")
                .containsPattern("Max-Age=604(799|800);");
        assertThat(value(setCookie)).hasSizeGreaterThanOrEqualTo(43);

        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"companyName": "Acme %1$s", "name": "Ada", "email": "%1$s@example.com",
                         "password": "correct-horse-battery"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("HttpOnly")));
    }

    @Test
    void refreshingGivesANewAccessTokenAndRotatesTheCookie() throws Exception {
        String first = signIn();

        MvcResult refreshed = mvc.perform(refresh(first)).andExpect(status().isOk()).andReturn();

        String second = value(refreshed.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(second).isNotBlank().isNotEqualTo(first);
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(refreshed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.userId().toString()));
        mvc.perform(refresh(second)).andExpect(status().isOk());
    }

    @Test
    void aRefreshTokenUsedTwiceEndsTheWholeSession() throws Exception {
        String first = signIn();
        String second = value(mvc.perform(refresh(first)).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE));
        jdbc.sql("UPDATE refresh_token SET used_at = used_at - interval '1 minute' WHERE token_hash = :hash")
                .param("hash", Hashing.sha256Hex(first))
                .update(); // past the grace period: this is a copy, not a race

        mvc.perform(refresh(first)).andExpect(status().isUnauthorized()).andExpect(cookieCleared());

        // The legitimate token dies with the copy: we cannot tell which holder is the thief
        mvc.perform(refresh(second)).andExpect(status().isUnauthorized());
        assertThat(revokedReason(second)).isEqualTo("REUSED");
    }

    @Test
    void refreshesRacingWithTheSameTokenAllSucceedWithoutEndingTheSession() throws Exception {
        String first = signIn();

        List<Future<MvcResult>> futures = Concurrently.run(4, i -> () -> mvc.perform(refresh(first)).andReturn());

        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get());
        }
        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        List<String> rotated = results.stream()
                .map(result -> result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .filter(Objects::nonNull)
                .toList();
        assertThat(rotated).as("exactly one request rotates the token").hasSize(1);
        mvc.perform(refresh(value(rotated.getFirst()))).andExpect(status().isOk());
    }

    @Test
    void aSessionEndsWhenIdleTooLongAndAtItsMaximumLifetime() throws Exception {
        String idle = signIn();
        jdbc.sql("UPDATE refresh_token SET expires_at = now() - interval '1 second' WHERE token_hash = :hash")
                .param("hash", Hashing.sha256Hex(idle))
                .update();
        mvc.perform(refresh(idle)).andExpect(status().isUnauthorized()).andExpect(cookieCleared());

        String old = signIn();
        setSessionExpiry(old, "now() + interval '1 hour'");
        String setCookie = mvc.perform(refresh(old)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(maxAge(setCookie)).as("never outlives the session").isBetween(3590, 3600);

        setSessionExpiry(old, "now() - interval '1 second'");
        mvc.perform(refresh(value(setCookie))).andExpect(status().isUnauthorized());
    }

    @Test
    void signingOutEndsTheSessionForGood() throws Exception {
        String session = signIn();

        mvc.perform(logout(session)).andExpect(status().isNoContent()).andExpect(cookieCleared());

        mvc.perform(refresh(session)).andExpect(status().isUnauthorized());
        assertThat(revokedReason(session)).isEqualTo("SIGNED_OUT");
        // Signing out always succeeds: twice, or without a session
        mvc.perform(logout(session)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").header(AuthController.CSRF_HEADER, "OpsPilot"))
                .andExpect(status().isNoContent());
    }

    @Test
    void signingInAgainEndsTheBrowsersPreviousSession() throws Exception {
        String previous = signIn();

        mvc.perform(login(user.email()).cookie(new Cookie(SessionCookie.NAME, previous))).andExpect(status().isOk());

        mvc.perform(refresh(previous)).andExpect(status().isUnauthorized());
    }

    @Test
    void cookieEndpointsRefuseRequestsOtherSitesCouldForge() throws Exception {
        String session = signIn();

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(SessionCookie.NAME, session)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString(AuthController.CSRF_HEADER)));
        mvc.perform(refresh(session).header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mvc.perform(refresh(session).header("Sec-Fetch-Site", "same-site")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(SessionCookie.NAME, session)))
                .andExpect(status().isForbidden());

        // None of the refused requests used or ended the session
        mvc.perform(refresh(session).header("Sec-Fetch-Site", "same-origin")).andExpect(status().isOk());
    }

    @Test
    void unknownAndMissingTokensAreRejected() throws Exception {
        mvc.perform(refresh("not-a-real-token")).andExpect(status().isUnauthorized()).andExpect(cookieCleared());
        mvc.perform(post("/api/v1/auth/refresh").header(AuthController.CSRF_HEADER, "OpsPilot"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredAccessTokenSentAlongDoesNotBlockTheRefresh() throws Exception {
        mvc.perform(refresh(signIn()).header(HttpHeaders.AUTHORIZATION, "Bearer expired.or.tampered"))
                .andExpect(status().isOk());
    }

    @Test
    void aRefreshPicksUpTheUsersCurrentRole() throws Exception {
        String session = signIn();
        jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE id = :id").param("id", user.userId()).update();

        String token = accessToken(mvc.perform(refresh(session)).andExpect(status().isOk()).andReturn());

        mvc.perform(get("/api/v1/api-keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void sessionsThatEndedOverADayAgoAreDeleted() throws Exception {
        String ended = signIn();
        String live = signIn();
        jdbc.sql("""
                UPDATE user_session SET revoked_at = now() - interval '2 days', revoked_reason = 'SIGNED_OUT'
                WHERE id = (SELECT session_id FROM refresh_token WHERE token_hash = :hash)
                """)
                .param("hash", Hashing.sha256Hex(ended))
                .update();

        sessions.deleteEnded();

        assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE token_hash = :hash")
                .param("hash", Hashing.sha256Hex(ended))
                .query(Integer.class)
                .single()).isZero();
        mvc.perform(refresh(live)).andExpect(status().isOk());
    }

    /** @return the session cookie's value */
    private String signIn() throws Exception {
        return value(mvc.perform(login(user.email())).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE));
    }

    private MockHttpServletRequestBuilder login(String email) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "password": "%s"}
                """.formatted(email, TestFixtures.PASSWORD));
    }

    /** As the dashboard sends it. */
    private static MockHttpServletRequestBuilder refresh(String session) {
        return post("/api/v1/auth/refresh")
                .cookie(new Cookie(SessionCookie.NAME, session))
                .header(AuthController.CSRF_HEADER, "OpsPilot");
    }

    private static MockHttpServletRequestBuilder logout(String session) {
        return post("/api/v1/auth/logout")
                .cookie(new Cookie(SessionCookie.NAME, session))
                .header(AuthController.CSRF_HEADER, "OpsPilot");
    }

    private static ResultMatcher cookieCleared() {
        return header().string(HttpHeaders.SET_COOKIE,
                Matchers.allOf(Matchers.startsWith(SessionCookie.NAME + "=;"), Matchers.containsString("Max-Age=0")));
    }

    private void setSessionExpiry(String session, String expression) {
        jdbc.sql("UPDATE user_session SET expires_at = " + expression
                        + " WHERE id = (SELECT session_id FROM refresh_token WHERE token_hash = :hash)")
                .param("hash", Hashing.sha256Hex(session))
                .update();
    }

    private String revokedReason(String session) {
        return jdbc.sql("""
                SELECT s.revoked_reason FROM user_session s JOIN refresh_token t ON t.session_id = s.id
                WHERE t.token_hash = :hash
                """)
                .param("hash", Hashing.sha256Hex(session))
                .query(String.class)
                .single();
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.accessToken");
    }

    private static String value(String setCookie) {
        Matcher matcher = COOKIE_VALUE.matcher(Objects.requireNonNull(setCookie, "no Set-Cookie header"));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static int maxAge(String setCookie) {
        Matcher matcher = MAX_AGE.matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
