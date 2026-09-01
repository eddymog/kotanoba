package com.kotanoba.lemma;

import com.kotanoba.lemma.LemmaBulkUpsertRepository.LemmaCandidate;
import com.kotanoba.nlp.NlpTokenizerClient;
import com.kotanoba.nlp.TokenizedWord;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves a (term, reading) pair from the frequency list (design.md §9b) to
 * a real lemma id, creating the lemma row if this word has never been
 * imported before.
 *
 * <p>Always goes through a real Sudachi call rather than guessing — reusing
 * {@link LemmaBulkUpsertRepository}'s existing upsert means a word that
 * normalizes to a script variant already in the database (できる when 出来る
 * was imported first, say) correctly resolves to that same lemma row instead
 * of creating a duplicate. Only when Sudachi can't cleanly resolve the single
 * word to exactly one token (rare — the frequency list is already
 * dictionary-form text, see design.md §9b's spot-check) does this fall back
 * to a placeholder part_of_speech built from the raw term/reading.
 */
@Component
public class VocabularyLemmaResolver {

    private static final String PLACEHOLDER_PART_OF_SPEECH = "UNKNOWN";

    private final NlpTokenizerClient nlpTokenizerClient;
    private final LemmaBulkUpsertRepository lemmaBulkUpsertRepository;

    public VocabularyLemmaResolver(NlpTokenizerClient nlpTokenizerClient, LemmaBulkUpsertRepository lemmaBulkUpsertRepository) {
        this.nlpTokenizerClient = nlpTokenizerClient;
        this.lemmaBulkUpsertRepository = lemmaBulkUpsertRepository;
    }

    public long findOrCreate(String term, String reading) {
        LemmaCandidate candidate = resolveCandidate(term, reading);
        Map<LemmaBulkUpsertRepository.LemmaKey, Long> result =
            lemmaBulkUpsertRepository.upsertAll(List.of(candidate));
        return result.get(new LemmaBulkUpsertRepository.LemmaKey(candidate.normalizedForm(), candidate.partOfSpeech()));
    }

    private LemmaCandidate resolveCandidate(String term, String reading) {
        List<TokenizedWord> tokens = nlpTokenizerClient.tokenize(term).tokens().stream()
            .filter(TokenizedWord::isWord)
            .toList();

        if (tokens.size() == 1) {
            TokenizedWord token = tokens.get(0);
            return new LemmaCandidate(token.normalizedForm(), token.dictionaryForm(), token.reading(), token.partOfSpeech());
        }

        // Sudachi split it into more than one token, or found nothing word-like
        // (rare for already-dictionary-form frequency-list text) — fall back
        // rather than fail the whole request.
        return new LemmaCandidate(term, term, reading, PLACEHOLDER_PART_OF_SPEECH);
    }
}
