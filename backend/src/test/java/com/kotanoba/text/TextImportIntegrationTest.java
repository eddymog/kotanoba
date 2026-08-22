package com.kotanoba.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.kotanoba.lemma.LemmaStatus;
import com.kotanoba.lemma.SetLemmaStatusRequest;
import com.kotanoba.user.AuthResponse;
import com.kotanoba.user.RegisterRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * First backend integration test — claude.md calls for Testcontainers against
 * real Postgres, and this is Slice 1's one true end-to-end path: paste text,
 * tokenize, persist. Deliberately real on both sides rather than mocked:
 *
 * <ul>
 *   <li>Real Postgres (Testcontainers) — Flyway runs its actual migrations at
 *       context startup, so this also doubles as a migration smoke test.
 *   <li>Real NLP container, built from {@code nlp/Dockerfile} — verifies the
 *       actual Sudachi call, not a stub standing in for it. Sudachi's own
 *       tokenization correctness (script-variant normalization, contextual
 *       readings, etc.) is already covered by {@code nlp/tests/}; this test's
 *       job is the backend's own correctness — did tokens actually turn into
 *       {@code lemma}/{@code word_form}/{@code text_token} rows.
 * </ul>
 *
 * Every endpoint under test now requires a real JWT (real auth landed after
 * this test was first written), so each test registers its own throwaway
 * user first — a fresh email per test avoids a 409 from the shared Postgres
 * container, and it doubles as free per-test data isolation.
 *
 * Containers are {@code static} + class-level, so both are started once and
 * reused across every {@code @Test} in this class (claude.md: "reuse
 * containers"), not restarted per test.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class TextImportIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("kotanoba")
        .withUsername("kotanoba")
        .withPassword("kotanoba");

    @Container
    static GenericContainer<?> nlp = new GenericContainer<>(
            new ImageFromDockerfile().withDockerfile(Path.of("../nlp/Dockerfile"))
        )
        .withExposedPorts(8000)
        .waitingFor(Wait.forHttp("/health").forStatusCode(200))
        .withStartupTimeout(Duration.ofMinutes(3)); // first run builds the image; Sudachi deps are the slow part

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("nlp-service.base-url",
            () -> "http://" + nlp.getHost() + ":" + nlp.getMappedPort(8000));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpHeaders authHeaders;

    @BeforeEach
    void registerAndAuthenticate() {
        var request = new RegisterRequest(UUID.randomUUID() + "@example.test", "integration-test-password");
        AuthResponse auth = restTemplate.postForObject("/api/auth/register", request, AuthResponse.class);
        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(auth.accessToken());
    }

    private <T> ResponseEntity<T> authedPost(String url, Object body, Class<T> responseType) {
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, authHeaders), responseType);
    }

    private void authedPut(String url, Object body, Object... uriVariables) {
        restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, authHeaders), Void.class, uriVariables);
    }

    @Test
    void importingRealJapaneseTextPersistsTextLemmasAndTokens() {
        var request = new ImportTextRequest(null, "今日は東京都庁に行きました。彼女は日本語が出来る。");

        ResponseEntity<TextSummaryResponse> response = authedPost("/api/texts", request, TextSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TextSummaryResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isNotNull();
        assertThat(body.tokenCount()).isGreaterThan(0);
        assertThat(body.distinctLemmaCount()).isGreaterThan(0);

        // text_token row count must match the reported token count exactly —
        // this is the JDBC-batch write path claude.md calls out specifically
        // (not saveAll()), so an off-by-one here would be a real regression.
        Integer tokenRowCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM text_token WHERE text_id = ?", Integer.class, body.id());
        assertThat(tokenRowCount).isEqualTo(body.tokenCount());

        // lemma rows exist for every id in this text's lemma_ids, and are
        // genuinely deduplicated: 今日は... contains repeated particles (は
        // appears twice), so distinct lemmas must be fewer than total word
        // tokens, not one row per occurrence. Scoped to this text's own
        // lemma_ids, not a global table count — the Postgres container is
        // reused across every @Test in this class (claude.md: "reuse
        // containers"), so a global count would depend on execution order.
        Integer lemmaRowCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM lemma WHERE id = ANY (SELECT unnest(lemma_ids) FROM text WHERE id = ?)",
            Integer.class, body.id());
        assertThat(lemmaRowCount).isEqualTo(body.distinctLemmaCount());

        // word_form recorded at least one surface form for one of this
        // text's own lemmas (design.md §2: a record of what was observed,
        // not consulted on the resolve path) — scoped for the same reason.
        Integer wordFormRowCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM word_form WHERE lemma_id = ANY (SELECT unnest(lemma_ids) FROM text WHERE id = ?)",
            Integer.class, body.id());
        assertThat(wordFormRowCount).isGreaterThan(0);

        // text.lemma_ids is the durable array copy the Slice 3 bitmap will be
        // built from (design.md §2) — must be populated, not left empty.
        Integer lemmaIdsLength = jdbcTemplate.queryForObject(
            "SELECT array_length(lemma_ids, 1) FROM text WHERE id = ?", Integer.class, body.id());
        assertThat(lemmaIdsLength).isEqualTo(body.distinctLemmaCount());
    }

    @Test
    void settingLemmaStatusUpsertsRatherThanDuplicates() {
        var request = new ImportTextRequest(null, "猫が好きです。");
        TextSummaryResponse text =
            authedPost("/api/texts", request, TextSummaryResponse.class).getBody();

        Long lemmaId = jdbcTemplate.queryForObject(
            "SELECT unnest(lemma_ids) FROM text WHERE id = ? LIMIT 1", Long.class, text.id());

        authedPut("/api/lemmas/{id}/status", new SetLemmaStatusRequest(LemmaStatus.KNOWN), lemmaId);
        authedPut("/api/lemmas/{id}/status", new SetLemmaStatusRequest(LemmaStatus.LEARNING), lemmaId);

        // Composite PK (user_id, lemma_id) — decision #10's whole point is
        // that this is one row overwritten, never a second row appended.
        Integer statusRowCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM user_lemma_status WHERE lemma_id = ?", Integer.class, lemmaId);
        assertThat(statusRowCount).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM user_lemma_status WHERE lemma_id = ?", String.class, lemmaId);
        assertThat(status).isEqualTo("LEARNING");
    }

    @Test
    void differentUsersDoNotSeeEachOthersTexts() {
        authedPost("/api/texts", new ImportTextRequest(null, "犬が好きです。"), TextSummaryResponse.class);

        // A second, unrelated user — re-registering rotates authHeaders to
        // this new user's token.
        registerAndAuthenticate();

        ResponseEntity<TextSummaryResponse[]> response =
            restTemplate.exchange("/api/texts", HttpMethod.GET, new HttpEntity<>(authHeaders), TextSummaryResponse[].class);

        assertThat(response.getBody()).isEmpty();
    }

    /**
     * Regression test for a real bug: a ResponseStatusException (this app's
     * standard way to signal 404/409/etc.) is delivered via sendError(),
     * which makes the servlet container internally forward the request to
     * /error to render it — and that forwarded request runs back through the
     * entire Spring Security filter chain. /error wasn't permitted, so
     * anyRequest().authenticated() denied that second pass and silently
     * overwrote the controller's real status with 401. Every
     * ResponseStatusException in the app was affected, not just this one —
     * caught by manually tracing a real request with Spring Security's TRACE
     * logging, not by any existing test, which is exactly why this exists
     * now (see SecurityConfig's permitAll("/error") and design.md §11).
     */
    @Test
    void responseStatusExceptionsReturnTheirRealStatusNotAGenericUnauthorized() {
        ResponseEntity<Void> notFound = restTemplate.exchange(
            "/api/texts/999999999", HttpMethod.GET, new HttpEntity<>(authHeaders), Void.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
