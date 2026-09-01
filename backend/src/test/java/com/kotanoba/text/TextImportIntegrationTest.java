package com.kotanoba.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.kotanoba.lemma.LemmaStatus;
import com.kotanoba.lemma.OtherVocabularyPageResponse;
import com.kotanoba.lemma.SetLemmaStatusRequest;
import com.kotanoba.lemma.SetVocabularyStatusRequest;
import com.kotanoba.lemma.VocabularyPageResponse;
import com.kotanoba.lemma.VocabularyStatsResponse;
import com.kotanoba.user.AuthResponse;
import com.kotanoba.user.RegisterRequest;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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
import org.springframework.web.util.UriComponentsBuilder;
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
    void libraryListIsSortedByFrequencyWeightedDifficultyDescending() {
        // Same user, two texts. Marking every lemma in one KNOWN should push
        // its score toward 1.0 and put it first — the other text's lemmas
        // stay NEW, so it scores lower and sorts second. Real word_frequency
        // data (Flyway's V5 seed migration) drives the weighting, so this
        // also doubles as an end-to-end check that the seed actually loaded.
        TextSummaryResponse harderText =
            authedPost("/api/texts", new ImportTextRequest(null, "図書館で難解な論文を精読する。"), TextSummaryResponse.class)
                .getBody();
        TextSummaryResponse easierText =
            authedPost("/api/texts", new ImportTextRequest(null, "猫が好きです。"), TextSummaryResponse.class)
                .getBody();

        List<Long> easierLemmaIds = jdbcTemplate.queryForList(
            "SELECT unnest(lemma_ids) FROM text WHERE id = ?", Long.class, easierText.id());
        for (Long lemmaId : easierLemmaIds) {
            authedPut("/api/lemmas/{id}/status", new SetLemmaStatusRequest(LemmaStatus.KNOWN), lemmaId);
        }

        ResponseEntity<TextLibraryPageResponse> response = restTemplate.exchange(
            "/api/texts", HttpMethod.GET, new HttpEntity<>(authHeaders), TextLibraryPageResponse.class);

        List<TextSummaryResponse> library = Objects.requireNonNull(response.getBody()).texts();
        assertThat(library).hasSize(2);
        assertThat(library.get(0).id()).isEqualTo(easierText.id());
        assertThat(library.get(0).difficultyScore()).isCloseTo(1.0, within(0.001));
        assertThat(library.get(1).id()).isEqualTo(harderText.id());
        assertThat(library.get(1).difficultyScore()).isLessThan(library.get(0).difficultyScore());
    }

    @Test
    void difficultyScoreWeighsByRealFrequencyRankNotJustKnownCount() {
        // Regression test for a real bug: word_frequency.reading is hiragana
        // (jpdb's source data) but lemma.reading_form is always Sudachi's
        // katakana — every join matching on both silently never matched on
        // reading, so every word fell through to the same unmatched-word
        // floor weight regardless of its real rank. That degrades scoring to
        // a flat known/total ratio, which the *other* test above can't catch:
        // "mark everything known" vs "mark nothing known" scores 1.0 vs 0.0
        // either way, whether real per-word weighting is active or not (see
        // V6's migration comment). This test can only pass if a very common
        // word (の, rank 1) is actually weighted far above an unranked one.
        var request = new ImportTextRequest(null, "曖昧模糊とする。"); // の isn't in this sentence; する (rank 11) stands in as the common word instead
        String commonWord = "する";
        String rareWord = "模糊";

        // User A: marks only the common, high-weight word known.
        TextSummaryResponse textA = authedPost("/api/texts", request, TextSummaryResponse.class).getBody();
        putStatusFor(textA.id(), commonWord, LemmaStatus.KNOWN);
        double scoreKnowingCommonWord = fetchScore(textA.id());

        // User B: marks only the rare, floor-weight word known.
        registerAndAuthenticate();
        TextSummaryResponse textB = authedPost("/api/texts", request, TextSummaryResponse.class).getBody();
        putStatusFor(textB.id(), rareWord, LemmaStatus.KNOWN);
        double scoreKnowingRareWord = fetchScore(textB.id());

        // Same "1 of N words known" in both cases — only real frequency
        // weighting can tell these apart, and it should tell them apart by a
        // lot: knowing the rank-11 word should score far higher than knowing
        // an unranked one.
        assertThat(scoreKnowingCommonWord).isGreaterThan(scoreKnowingRareWord + 0.3);
    }

    private void putStatusFor(long textId, String dictionaryForm, LemmaStatus status) {
        Long lemmaId = jdbcTemplate.queryForObject(
            """
            SELECT l.id FROM lemma l
            JOIN text t ON l.id = ANY(t.lemma_ids)
            WHERE t.id = ? AND l.dictionary_form = ?
            """,
            Long.class, textId, dictionaryForm);
        authedPut("/api/lemmas/{id}/status", new SetLemmaStatusRequest(status), lemmaId);
    }

    private double fetchScore(long textId) {
        ResponseEntity<TextLibraryPageResponse> response = restTemplate.exchange(
            "/api/texts", HttpMethod.GET, new HttpEntity<>(authHeaders), TextLibraryPageResponse.class);
        return Objects.requireNonNull(response.getBody()).texts().stream()
            .filter(t -> t.id() == textId)
            .findFirst()
            .orElseThrow()
            .difficultyScore();
    }

    @Test
    void differentUsersDoNotSeeEachOthersTexts() {
        authedPost("/api/texts", new ImportTextRequest(null, "犬が好きです。"), TextSummaryResponse.class);

        // A second, unrelated user — re-registering rotates authHeaders to
        // this new user's token.
        registerAndAuthenticate();

        ResponseEntity<TextLibraryPageResponse> response =
            restTemplate.exchange("/api/texts", HttpMethod.GET, new HttpEntity<>(authHeaders), TextLibraryPageResponse.class);

        assertThat(Objects.requireNonNull(response.getBody()).texts()).isEmpty();
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

    @Test
    void vocabularyPageOneListsTop100WordsInRankOrder() {
        ResponseEntity<VocabularyPageResponse> response = restTemplate.exchange(
            "/api/vocabulary?page=1", HttpMethod.GET, new HttpEntity<>(authHeaders), VocabularyPageResponse.class);

        VocabularyPageResponse page = Objects.requireNonNull(response.getBody());
        assertThat(page.page()).isEqualTo(1);
        // Offset pagination (LIMIT 100 OFFSET 0), not a rank band — always
        // exactly 100 rows regardless of the seed data's occasional dedup
        // gaps (design.md §9b, e.g. rank 51 is genuinely missing), so a page
        // can extend slightly past rank 100 to make up a full 100 words.
        assertThat(page.words()).hasSize(100);
        assertThat(page.words().get(0).rank()).isEqualTo(1);
        assertThat(page.words()).isSortedAccordingTo((a, b) -> a.rank() - b.rank());
        // Fresh user, nothing marked yet — everything defaults to NEW.
        assertThat(page.words()).allMatch(w -> w.status() == LemmaStatus.NEW);
        // の, rank 1, is a real Sudachi-resolved particle (V8's seed) — not
        // the placeholder/blank value it'd be if the batch resolution or
        // its column wiring silently no-op'd (same class of bug as V6's).
        assertThat(page.words().get(0).partOfSpeech()).startsWith("助詞");
        // の's senses (design.md §18's dictionary_entry seed) should include
        // the possessive-particle sense, not 野's unrelated "field" — proof
        // the normalized_form/reading match picked the right homophone entry.
        assertThat(page.words().get(0).senses()).anyMatch(s -> s.toLowerCase().contains("possessive"));
    }

    @Test
    void settingVocabularyStatusForAWordNeverImportedCreatesItsLemmaThroughRealTokenization() {
        // 杞憂 ("needless worry") isn't used by any other test in this class
        // and (per design.md §9b's spot-check scale) is very unlikely to be
        // in the top 10k — this exercises VocabularyLemmaResolver's
        // create-via-Sudachi path, not the find-existing one.
        String term = "杞憂";
        String reading = "キユウ";

        ResponseEntity<Void> putResponse = restTemplate.exchange(
            "/api/vocabulary/status", HttpMethod.PUT, new HttpEntity<>(new SetVocabularyStatusRequest(term, reading, LemmaStatus.KNOWN), authHeaders),
            Void.class);
        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Integer lemmaCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM lemma WHERE dictionary_form = ?", Integer.class, term);
        assertThat(lemmaCount).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
            """
            SELECT uls.status FROM user_lemma_status uls
            JOIN lemma l ON l.id = uls.lemma_id
            WHERE l.dictionary_form = ?
            """,
            String.class, term);
        assertThat(status).isEqualTo("KNOWN");
    }

    @Test
    void vocabularyPageFiltersByStatus() {
        String term = "を";
        String reading = "ヲ";
        restTemplate.exchange(
            "/api/vocabulary/status", HttpMethod.PUT,
            new HttpEntity<>(new SetVocabularyStatusRequest(term, reading, LemmaStatus.KNOWN), authHeaders),
            Void.class);

        ResponseEntity<VocabularyPageResponse> knownResponse = restTemplate.exchange(
            "/api/vocabulary?page=1&status=KNOWN", HttpMethod.GET, new HttpEntity<>(authHeaders), VocabularyPageResponse.class);
        VocabularyPageResponse knownPage = Objects.requireNonNull(knownResponse.getBody());
        assertThat(knownPage.words()).allMatch(w -> w.status() == LemmaStatus.KNOWN);
        assertThat(knownPage.words()).anyMatch(w -> w.term().equals(term) && w.reading().equals(reading));

        // Same word must not show up under a different filter — proof this
        // is a real WHERE clause over computed status, not a filter that
        // silently no-ops and returns everything regardless of the param.
        ResponseEntity<VocabularyPageResponse> newResponse = restTemplate.exchange(
            "/api/vocabulary?page=1&status=NEW", HttpMethod.GET, new HttpEntity<>(authHeaders), VocabularyPageResponse.class);
        VocabularyPageResponse newPage = Objects.requireNonNull(newResponse.getBody());
        assertThat(newPage.words()).allMatch(w -> w.status() == LemmaStatus.NEW);
        assertThat(newPage.words()).noneMatch(w -> w.term().equals(term) && w.reading().equals(reading));
    }

    @Test
    void vocabularyPageFiltersByPartOfSpeechAndCombinesWithStatusFilter() {
        // 食べる, a real verb -- distinct from を (a particle) used in the
        // status-only filter test above, so the two tests' assertions can't
        // interfere with each other.
        String term = "食べる";
        String reading = "タベル";
        restTemplate.exchange(
            "/api/vocabulary/status", HttpMethod.PUT,
            new HttpEntity<>(new SetVocabularyStatusRequest(term, reading, LemmaStatus.KNOWN), authHeaders),
            Void.class);

        // UriComponentsBuilder + .encode(), not a hand-encoded query string:
        // exchange(String, ...) runs the value through TestRestTemplate's
        // own URI template handling too, so a pre-percent-encoded Japanese
        // string gets encoded a second time and matches nothing server-side
        // — caught by actually running this against the real endpoint
        // (a plain curl call to the same URL worked fine), not by inspection.
        ResponseEntity<VocabularyPageResponse> verbsResponse = restTemplate.exchange(
            vocabularyUri(1, null, "動詞"), HttpMethod.GET, new HttpEntity<>(authHeaders), VocabularyPageResponse.class);
        VocabularyPageResponse verbsPage = Objects.requireNonNull(verbsResponse.getBody());
        assertThat(verbsPage.words()).isNotEmpty();
        assertThat(verbsPage.words()).allMatch(w -> w.partOfSpeech() != null && w.partOfSpeech().contains("動詞"));

        // Combined filter (AND): KNOWN + Verb should include 食べる...
        ResponseEntity<VocabularyPageResponse> combinedResponse = restTemplate.exchange(
            vocabularyUri(1, LemmaStatus.KNOWN, "動詞"), HttpMethod.GET, new HttpEntity<>(authHeaders), VocabularyPageResponse.class);
        VocabularyPageResponse combinedPage = Objects.requireNonNull(combinedResponse.getBody());
        assertThat(combinedPage.words()).anyMatch(w -> w.term().equals(term));

        // ...but KNOWN + Particle should not, since 食べる isn't a particle
        // — proof the two filters actually combine with AND, not OR.
        ResponseEntity<VocabularyPageResponse> mismatchResponse = restTemplate.exchange(
            vocabularyUri(1, LemmaStatus.KNOWN, "助詞"), HttpMethod.GET, new HttpEntity<>(authHeaders), VocabularyPageResponse.class);
        VocabularyPageResponse mismatchPage = Objects.requireNonNull(mismatchResponse.getBody());
        assertThat(mismatchPage.words()).noneMatch(w -> w.term().equals(term));
    }

    private URI vocabularyUri(int page, LemmaStatus status, String pos) {
        var builder = UriComponentsBuilder.fromUriString(restTemplate.getRootUri() + "/api/vocabulary")
            .queryParam("page", page);
        if (status != null) {
            builder.queryParam("status", status);
        }
        if (pos != null) {
            builder.queryParam("pos", pos);
        }
        return builder.build().encode().toUri();
    }

    @Test
    void deletingATextRemovesItAndCascadesTokens() {
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "削除するテキスト。"), TextSummaryResponse.class).getBody();

        Integer tokenCountBefore = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM text_token WHERE text_id = ?", Integer.class, text.id());
        assertThat(tokenCountBefore).isGreaterThan(0);

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
            "/api/texts/{id}", HttpMethod.DELETE, new HttpEntity<>(authHeaders), Void.class, text.id());
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Integer textCountAfter = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM text WHERE id = ?", Integer.class, text.id());
        assertThat(textCountAfter).isZero();
        // text_token's FK cascades (V1__initial_schema.sql) — not a separate
        // delete statement this endpoint has to get right on its own.
        Integer tokenCountAfter = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM text_token WHERE text_id = ?", Integer.class, text.id());
        assertThat(tokenCountAfter).isZero();

        ResponseEntity<Void> getAfterDelete = restTemplate.exchange(
            "/api/texts/{id}", HttpMethod.GET, new HttpEntity<>(authHeaders), Void.class, text.id());
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingAnotherUsersTextReturnsNotFoundAndDoesNotDelete() {
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "他人のテキスト。"), TextSummaryResponse.class).getBody();

        registerAndAuthenticate(); // rotates authHeaders to a second, unrelated user

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
            "/api/texts/{id}", HttpMethod.DELETE, new HttpEntity<>(authHeaders), Void.class, text.id());
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Integer stillExists = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM text WHERE id = ?", Integer.class, text.id());
        assertThat(stillExists).isEqualTo(1);
    }

    @Test
    void renamingATextUpdatesItsTitle() {
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "名前のないテキスト。"), TextSummaryResponse.class)
                .getBody();

        ResponseEntity<Void> renameResponse = restTemplate.exchange(
            "/api/texts/{id}/title", HttpMethod.PUT, new HttpEntity<>(new UpdateTextTitleRequest("My Renamed Text"), authHeaders),
            Void.class, Objects.requireNonNull(text).id());
        assertThat(renameResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<TextDetailResponse> getResponse = restTemplate.exchange(
            "/api/texts/{id}", HttpMethod.GET, new HttpEntity<>(authHeaders), TextDetailResponse.class, text.id());
        assertThat(Objects.requireNonNull(getResponse.getBody()).title()).isEqualTo("My Renamed Text");
    }

    @Test
    void renamingAnotherUsersTextReturnsNotFoundAndDoesNotRename() {
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "他人のテキスト。"), TextSummaryResponse.class).getBody();

        registerAndAuthenticate(); // rotates authHeaders to a second, unrelated user

        ResponseEntity<Void> renameResponse = restTemplate.exchange(
            "/api/texts/{id}/title", HttpMethod.PUT, new HttpEntity<>(new UpdateTextTitleRequest("Hijacked"), authHeaders),
            Void.class, Objects.requireNonNull(text).id());
        assertThat(renameResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String titleAfter = jdbcTemplate.queryForObject(
            "SELECT title FROM text WHERE id = ?", String.class, text.id());
        assertThat(titleAfter).isEqualTo("他人のテキスト。");
    }

    @Test
    void libraryListSearchFiltersByTitleCaseInsensitively() {
        authedPost("/api/texts", new ImportTextRequest("Weather Report", "天気がいいです。"), TextSummaryResponse.class);
        authedPost("/api/texts", new ImportTextRequest("Cooking Notes", "料理を作ります。"), TextSummaryResponse.class);

        ResponseEntity<TextLibraryPageResponse> response = restTemplate.exchange(
            "/api/texts?q=weather", HttpMethod.GET, new HttpEntity<>(authHeaders), TextLibraryPageResponse.class);

        List<TextSummaryResponse> results = Objects.requireNonNull(response.getBody()).texts();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Weather Report");
    }

    @Test
    void librarySortRecentOrdersByImportTimeRegardlessOfDifficulty() {
        // Import the easier text FIRST (and mark its words all KNOWN), the
        // harder text SECOND (left untouched). Difficulty order puts the
        // easy text first regardless of import time; recency order must put
        // the harder text first instead, since it was imported later — the
        // two orders have to disagree here, or this test can't tell a real
        // ORDER BY change from ?sort=RECENT being silently ignored.
        TextSummaryResponse easierText =
            authedPost("/api/texts", new ImportTextRequest(null, "犬が好きです。"), TextSummaryResponse.class).getBody();
        TextSummaryResponse harderText =
            authedPost("/api/texts", new ImportTextRequest(null, "難解な専門用語。"), TextSummaryResponse.class).getBody();

        List<Long> easierLemmaIds = jdbcTemplate.queryForList(
            "SELECT unnest(lemma_ids) FROM text WHERE id = ?", Long.class, easierText.id());
        for (Long lemmaId : easierLemmaIds) {
            authedPut("/api/lemmas/{id}/status", new SetLemmaStatusRequest(LemmaStatus.KNOWN), lemmaId);
        }

        // Sanity check: default (difficulty) sort really does put the easy
        // text first, so the RECENT assertion below is a genuine reversal,
        // not a setup that happened to agree with both orders.
        ResponseEntity<TextLibraryPageResponse> difficultyResponse = restTemplate.exchange(
            "/api/texts", HttpMethod.GET, new HttpEntity<>(authHeaders), TextLibraryPageResponse.class);
        assertThat(Objects.requireNonNull(difficultyResponse.getBody()).texts().get(0).id()).isEqualTo(easierText.id());

        ResponseEntity<TextLibraryPageResponse> recentResponse = restTemplate.exchange(
            "/api/texts?sort=RECENT", HttpMethod.GET, new HttpEntity<>(authHeaders), TextLibraryPageResponse.class);
        List<TextSummaryResponse> recentLibrary = Objects.requireNonNull(recentResponse.getBody()).texts();

        assertThat(recentLibrary.get(0).id()).isEqualTo(harderText.id());
        assertThat(recentLibrary.get(1).id()).isEqualTo(easierText.id());
    }

    @Test
    void libraryListPaginatesWithoutErrorPastTheLastPage() {
        authedPost("/api/texts", new ImportTextRequest(null, "一つ目のテキスト。"), TextSummaryResponse.class);
        authedPost("/api/texts", new ImportTextRequest(null, "二つ目のテキスト。"), TextSummaryResponse.class);

        ResponseEntity<TextLibraryPageResponse> pageOne = restTemplate.exchange(
            "/api/texts?page=1", HttpMethod.GET, new HttpEntity<>(authHeaders), TextLibraryPageResponse.class);
        TextLibraryPageResponse pageOneBody = Objects.requireNonNull(pageOne.getBody());
        assertThat(pageOneBody.texts()).hasSize(2);
        assertThat(pageOneBody.totalPages()).isEqualTo(1);

        // Page 2 of a 1-page result: past the end, but a real (empty) page,
        // not an error — same "out-of-range page is just empty" contract as
        // the vocabulary browse page.
        ResponseEntity<TextLibraryPageResponse> pageTwo = restTemplate.exchange(
            "/api/texts?page=2", HttpMethod.GET, new HttpEntity<>(authHeaders), TextLibraryPageResponse.class);
        TextLibraryPageResponse pageTwoBody = Objects.requireNonNull(pageTwo.getBody());
        assertThat(pageTwo.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(pageTwoBody.texts()).isEmpty();
    }

    @Test
    void openingATextSetsLastOpenedAt() {
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "開いたテキスト。"), TextSummaryResponse.class).getBody();

        Object beforeOpen = jdbcTemplate.queryForObject(
            "SELECT last_opened_at FROM text WHERE id = ?", Object.class, text.id());
        assertThat(beforeOpen).isNull();

        restTemplate.exchange(
            "/api/texts/{id}", HttpMethod.GET, new HttpEntity<>(authHeaders), TextDetailResponse.class, text.id());

        Object afterOpen = jdbcTemplate.queryForObject(
            "SELECT last_opened_at FROM text WHERE id = ?", Object.class, text.id());
        assertThat(afterOpen).isNotNull();
    }

    @Test
    void readerTokensCarryMeaningAndPartOfSpeechForWordsInTheFrequencyList() {
        // 猫 (rank ~1509) has a real JMdict match (design.md §13); this
        // proves the read path's word_frequency join actually reaches it via
        // lemma.dictionary_form/reading_form, not just the vocabulary
        // browse page's own separate query.
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "猫が好きです。"), TextSummaryResponse.class).getBody();

        ResponseEntity<TextDetailResponse> response = restTemplate.exchange(
            "/api/texts/{id}", HttpMethod.GET, new HttpEntity<>(authHeaders), TextDetailResponse.class, text.id());
        TextDetailResponse detail = Objects.requireNonNull(response.getBody());

        TokenView catToken = detail.tokens().stream()
            .filter(t -> t.surfaceText().equals("猫"))
            .findFirst()
            .orElseThrow();
        assertThat(catToken.partOfSpeech()).startsWith("名詞");
        assertThat(catToken.senses()).anyMatch(s -> s.toLowerCase().contains("cat"));
    }

    @Test
    void savingReadPositionPersistsAndIsReturnedOnNextOpen() {
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "これはテストです。"), TextSummaryResponse.class).getBody();

        ResponseEntity<Void> putResponse = restTemplate.exchange(
            "/api/texts/{id}/position", HttpMethod.PUT, new HttpEntity<>(new SaveReadPositionRequest(3), authHeaders),
            Void.class, text.id());
        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<TextDetailResponse> getResponse = restTemplate.exchange(
            "/api/texts/{id}", HttpMethod.GET, new HttpEntity<>(authHeaders), TextDetailResponse.class, text.id());
        assertThat(Objects.requireNonNull(getResponse.getBody()).lastReadPosition()).isEqualTo(3);
    }

    @Test
    void savingReadPositionForAnotherUsersTextReturnsNotFound() {
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "他人のテキスト。"), TextSummaryResponse.class).getBody();

        registerAndAuthenticate(); // rotates authHeaders to a second, unrelated user

        ResponseEntity<Void> putResponse = restTemplate.exchange(
            "/api/texts/{id}/position", HttpMethod.PUT, new HttpEntity<>(new SaveReadPositionRequest(1), authHeaders),
            Void.class, text.id());
        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void readerTokensResolveMeaningForAScriptVariantEvenAfterWordFrequencyDedup() {
        // 出来る's own word_frequency row (rank 1302) was deleted by V16's
        // dedup — できる (rank 94) survived as the canonical spelling for
        // this normalized_form. If the read path still joined on
        // dictionary_form (design.md §16's bug), this text's own 出来る
        // token would find no word_frequency row at all post-dedup and get
        // null meaning/POS, not just the "wrong" one.
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "出来ることをやる。"), TextSummaryResponse.class).getBody();

        ResponseEntity<TextDetailResponse> response = restTemplate.exchange(
            "/api/texts/{id}", HttpMethod.GET, new HttpEntity<>(authHeaders), TextDetailResponse.class, text.id());
        TokenView dekiruToken = Objects.requireNonNull(response.getBody()).tokens().stream()
            .filter(t -> t.surfaceText().equals("出来る"))
            .findFirst()
            .orElseThrow();

        assertThat(dekiruToken.partOfSpeech()).startsWith("動詞");
        assertThat(dekiruToken.senses()).isNotNull().isNotEmpty();
    }

    @Test
    void vocabularyPageShowsKnownStatusRegardlessOfWhichScriptVariantWasMarked() {
        // Mark 出来る (via the reader's real import + status path) KNOWN,
        // then check できる — a different lemma.dictionary_form, the same
        // normalized_form — on the vocabulary browse page. They must share
        // a lemma row (LemmaBulkUpsertRepository upserts on
        // (normalized_form, part_of_speech), already true before today's
        // fix), so this also doubles as end-to-end proof the vocabulary
        // page's own LATERAL join (design.md §16) finds that shared lemma
        // via normalized_form.
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "出来ることをやる。"), TextSummaryResponse.class).getBody();
        Long lemmaId = jdbcTemplate.queryForObject(
            """
            SELECT l.id FROM lemma l
            JOIN text t ON l.id = ANY(t.lemma_ids)
            WHERE t.id = ? AND l.dictionary_form = '出来る'
            """,
            Long.class, text.id());
        authedPut("/api/lemmas/{id}/status", new SetLemmaStatusRequest(LemmaStatus.KNOWN), lemmaId);

        ResponseEntity<VocabularyPageResponse> response = restTemplate.exchange(
            vocabularyUri(1, null, null), HttpMethod.GET, new HttpEntity<>(authHeaders), VocabularyPageResponse.class);
        var dekiruWord = Objects.requireNonNull(response.getBody()).words().stream()
            .filter(w -> w.term().equals("できる"))
            .findFirst()
            .orElseThrow();

        assertThat(dekiruWord.status()).isEqualTo(LemmaStatus.KNOWN);
    }

    @Test
    void otherVocabularyListShowsRichDefinitionAndExampleForAWordOutsideTop10k() {
        // いざこざ ("trouble; quarrel") isn't in the top 10k word_frequency
        // list, so it lands on the "other words" list (design.md §17) — but
        // design.md §18's dictionary_entry/word_example seeds cover far more
        // than the top 10k, so it still resolves a real, multi-sense
        // definition and an example sentence, not the "no definition
        // available" placeholder.
        authedPost("/api/texts", new ImportTextRequest(null, "隣人といざこざを起こした。"), TextSummaryResponse.class);

        ResponseEntity<OtherVocabularyPageResponse> response = restTemplate.exchange(
            "/api/vocabulary/other", HttpMethod.GET, new HttpEntity<>(authHeaders), OtherVocabularyPageResponse.class);
        var word = Objects.requireNonNull(response.getBody()).words().stream()
            .filter(w -> w.term().equals("いざこざ"))
            .findFirst()
            .orElseThrow();

        assertThat(word.senses()).anyMatch(s -> s.toLowerCase().contains("trouble"));
        assertThat(word.exampleJapanese()).isNotNull();
        assertThat(word.exampleEnglish()).isNotNull();
    }

    @Test
    void vocabularyStatsSplitsCountsBetweenTopAndOtherWords() {
        // Fresh user: every top-10k word defaults to NEW, and there are no
        // "other" words at all since nothing has been imported yet.
        VocabularyStatsResponse before = Objects.requireNonNull(restTemplate.exchange(
            "/api/vocabulary/stats", HttpMethod.GET, new HttpEntity<>(authHeaders), VocabularyStatsResponse.class
        ).getBody());
        assertThat(before.topWords().total()).isGreaterThan(9000);
        assertThat(before.topWords().newCount()).isEqualTo(before.topWords().total());
        assertThat(before.otherWords().total()).isZero();

        // Mark の (rank 1, top-10k) KNOWN via the vocabulary page's own
        // find-or-create endpoint.
        authedPut("/api/vocabulary/status", new SetVocabularyStatusRequest("の", "ノ", LemmaStatus.KNOWN));

        // Import a text containing いざこざ (outside the top 10k) and mark it
        // LEARNING via the reader's lemma-id-based endpoint. This one-word
        // sentence still tokenizes to more than one word row overall, but
        // いざこざ alone is enough to prove the split — the assertions below
        // only pin down いざこざ's own status, not the other words' count,
        // since a fuller sentence would pull in other real, unpredictable
        // "other" words too (design.md §17's whole feature is that this can
        // happen) rather than being a bug in this test.
        TextSummaryResponse text =
            authedPost("/api/texts", new ImportTextRequest(null, "いざこざ。"), TextSummaryResponse.class).getBody();
        Long izakozaLemmaId = jdbcTemplate.queryForObject(
            """
            SELECT l.id FROM lemma l
            JOIN text t ON l.id = ANY(t.lemma_ids)
            WHERE t.id = ? AND l.dictionary_form = 'いざこざ'
            """,
            Long.class, Objects.requireNonNull(text).id());
        authedPut("/api/lemmas/{id}/status", new SetLemmaStatusRequest(LemmaStatus.LEARNING), izakozaLemmaId);

        VocabularyStatsResponse after = Objects.requireNonNull(restTemplate.exchange(
            "/api/vocabulary/stats", HttpMethod.GET, new HttpEntity<>(authHeaders), VocabularyStatsResponse.class
        ).getBody());

        // Marking one top-10k word doesn't change how many top-10k words
        // there are in total, only how they're distributed across statuses.
        assertThat(after.topWords().total()).isEqualTo(before.topWords().total());
        assertThat(after.topWords().knownCount()).isEqualTo(1);
        assertThat(after.topWords().newCount()).isEqualTo(before.topWords().total() - 1);

        assertThat(after.otherWords().total()).isEqualTo(1);
        assertThat(after.otherWords().learningCount()).isEqualTo(1);
        assertThat(after.otherWords().newCount()).isZero();
    }
}
