package com.kotanoba.text;

import com.kotanoba.lemma.LemmaBulkUpsertRepository;
import com.kotanoba.lemma.LemmaBulkUpsertRepository.LemmaCandidate;
import com.kotanoba.lemma.LemmaBulkUpsertRepository.LemmaKey;
import com.kotanoba.lemma.WordFormWriter;
import com.kotanoba.nlp.NlpTokenizerClient;
import com.kotanoba.nlp.TokenizeResult;
import com.kotanoba.nlp.TokenizedWord;
import com.kotanoba.user.CurrentUser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Slice 1 import pipeline: paste-text -> tokenize -> store (design.md §4).
 *
 * <p>Synchronous, per decision #4 — this calls the NLP service inline and
 * blocks the request. The SKIP LOCKED queue is Slice 2 work; this method is
 * exactly what Slice 2's worker will call too, once the trigger changes from
 * an HTTP request to a job pickup.
 */
@Service
public class TextImportService {

    private final NlpTokenizerClient nlpClient;
    private final LemmaBulkUpsertRepository lemmaUpsertRepository;
    private final WordFormWriter wordFormWriter;
    private final TextDocumentRepository textDocumentRepository;
    private final TextTokenBatchWriter tokenBatchWriter;
    private final CurrentUser currentUser;

    public TextImportService(
        NlpTokenizerClient nlpClient,
        LemmaBulkUpsertRepository lemmaUpsertRepository,
        WordFormWriter wordFormWriter,
        TextDocumentRepository textDocumentRepository,
        TextTokenBatchWriter tokenBatchWriter,
        CurrentUser currentUser
    ) {
        this.nlpClient = nlpClient;
        this.lemmaUpsertRepository = lemmaUpsertRepository;
        this.wordFormWriter = wordFormWriter;
        this.textDocumentRepository = textDocumentRepository;
        this.tokenBatchWriter = tokenBatchWriter;
        this.currentUser = currentUser;
    }

    /**
     * Single transaction, default propagation and isolation: if the batch
     * insert throws, the text row and lemma upserts roll back with it rather
     * than leaving an orphaned text with zero tokens. Whether default
     * isolation stays adequate once Slice 2 introduces concurrent workers on
     * the same rows is exactly the kind of question the working agreement
     * reserves for you (claude.md: "transaction boundaries and isolation
     * choices") — flagging it here rather than deciding it silently.
     */
    @Transactional
    public TextDocument importText(String requestedTitle, String body) {
        TokenizeResult result = nlpClient.tokenize(body);

        Map<LemmaKey, LemmaCandidate> candidatesByKey = distinctWordCandidates(result.tokens());
        Map<LemmaKey, Long> lemmaIdsByKey = lemmaUpsertRepository.upsertAll(List.copyOf(candidatesByKey.values()));

        List<TokenRecord> tokenRecords = new ArrayList<>(result.tokens().size());
        List<String> wordFormSurfaces = new ArrayList<>();
        List<Long> wordFormLemmaIds = new ArrayList<>();
        int position = 0;
        for (TokenizedWord token : result.tokens()) {
            Long lemmaId = token.isWord()
                ? lemmaIdsByKey.get(new LemmaKey(token.normalizedForm(), token.partOfSpeech()))
                : null;

            tokenRecords.add(new TokenRecord(
                position++,
                token.charStart(),
                token.charEnd(),
                token.surface(),
                token.reading(),
                lemmaId,
                token.isWord()
            ));

            if (token.isWord()) {
                wordFormSurfaces.add(token.surface());
                wordFormLemmaIds.add(lemmaId);
            }
        }

        wordFormWriter.recordAll(wordFormSurfaces, wordFormLemmaIds);

        List<Long> distinctLemmaIds = List.copyOf(new LinkedHashSet<>(lemmaIdsByKey.values()));
        String title = (requestedTitle == null || requestedTitle.isBlank())
            ? deriveTitle(body)
            : requestedTitle;

        TextDocument text = new TextDocument(
            currentUser.id(), title, body, null, distinctLemmaIds, tokenRecords.size()
        );
        text = textDocumentRepository.save(text); // IDENTITY strategy: id is populated immediately

        tokenBatchWriter.insertAll(text.getId(), tokenRecords);

        return text;
    }

    private static Map<LemmaKey, LemmaCandidate> distinctWordCandidates(List<TokenizedWord> tokens) {
        // LinkedHashMap: the bulk upsert's ON CONFLICT target is
        // (normalized_form, part_of_speech), and Postgres rejects an INSERT
        // that hits the same conflict target twice within one statement — so
        // duplicates must be collapsed before they reach LemmaBulkUpsertRepository.
        Map<LemmaKey, LemmaCandidate> byKey = new LinkedHashMap<>();
        // Whether the reading currently stored for a key came from an
        // occurrence where the word appeared in its own dictionary form
        // (unconjugated) — design.md §17. A conjugated occurrence's reading
        // (いる conjugated as い+た gives イ, not いる's own イル) is only a
        // placeholder until a better occurrence is seen; dictionaryForm
        // itself stays first-write-wins, per LemmaBulkUpsertRepository's
        // javadoc, since Sudachi can report a different spelling variant's
        // dictionary_form across occurrences.
        Map<LemmaKey, Boolean> readingIsFromDictionaryForm = new HashMap<>();
        for (TokenizedWord token : tokens) {
            if (!token.isWord()) {
                continue;
            }
            LemmaKey key = new LemmaKey(token.normalizedForm(), token.partOfSpeech());
            boolean isUnconjugated = token.surface().equals(token.dictionaryForm());
            LemmaCandidate existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, new LemmaCandidate(
                    token.normalizedForm(), token.dictionaryForm(), token.reading(), token.partOfSpeech()
                ));
                readingIsFromDictionaryForm.put(key, isUnconjugated);
            } else if (isUnconjugated && !readingIsFromDictionaryForm.get(key)) {
                byKey.put(key, new LemmaCandidate(
                    existing.normalizedForm(), existing.dictionaryForm(), token.reading(), existing.partOfSpeech()
                ));
                readingIsFromDictionaryForm.put(key, true);
            }
        }
        return byKey;
    }

    private static final int DERIVED_TITLE_MAX_CHARS = 40;

    private static String deriveTitle(String body) {
        String trimmed = body.strip();
        if (trimmed.length() <= DERIVED_TITLE_MAX_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, DERIVED_TITLE_MAX_CHARS) + "…";
    }
}
