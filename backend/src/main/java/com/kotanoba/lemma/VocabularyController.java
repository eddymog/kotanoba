package com.kotanoba.lemma;

import com.kotanoba.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browsing the frequency reference (design.md §9b) as a vocabulary triage
 * tool — "which of the most common words do I already know" — with the
 * ability to set status directly from here, not just from the reader.
 */
@RestController
@Validated
public class VocabularyController {

    private static final int PAGE_SIZE = 100;

    private final VocabularyBrowseRepository browseRepository;
    private final OtherVocabularyRepository otherVocabularyRepository;
    private final VocabularyStatsRepository statsRepository;
    private final VocabularyLemmaResolver lemmaResolver;
    private final UserLemmaStatusRepository statusRepository;
    private final CurrentUser currentUser;

    public VocabularyController(
        VocabularyBrowseRepository browseRepository,
        OtherVocabularyRepository otherVocabularyRepository,
        VocabularyStatsRepository statsRepository,
        VocabularyLemmaResolver lemmaResolver,
        UserLemmaStatusRepository statusRepository,
        CurrentUser currentUser
    ) {
        this.browseRepository = browseRepository;
        this.otherVocabularyRepository = otherVocabularyRepository;
        this.statsRepository = statsRepository;
        this.lemmaResolver = lemmaResolver;
        this.statusRepository = statusRepository;
        this.currentUser = currentUser;
    }

    /**
     * design.md §9b/§13: PAGE_SIZE words per page, optionally filtered to one
     * status and/or one part-of-speech category (combined with AND — see
     * word_frequency.pos_categories, V11). Offset pagination (not rank
     * bands) so a filter's scattered matches paginate sensibly — see
     * VocabularyBrowseRepository's javadoc. pos isn't validated against a
     * known set: an unrecognized value just matches nothing, harmlessly.
     */
    @GetMapping("/api/vocabulary")
    public VocabularyPageResponse list(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(required = false) LemmaStatus status,
        @RequestParam(required = false) String pos
    ) {
        int totalMatching = browseRepository.countMatching(currentUser.id(), status, pos);
        int totalPages = Math.max(1, (int) Math.ceil(totalMatching / (double) PAGE_SIZE));
        List<VocabularyWordResponse> words = browseRepository.listPage(currentUser.id(), status, pos, page, PAGE_SIZE);
        return new VocabularyPageResponse(page, totalPages, words);
    }

    /**
     * design.md §17: words you've actually encountered (real lemma rows)
     * that fall outside the top 10k — the counterpart to list() above,
     * which only ever shows words inside that list. No find-or-create
     * status endpoint here: unlike the top-10k browse page, every word here
     * already has a lemma row by definition, so status changes go straight
     * to the existing PUT /api/lemmas/{id}/status.
     */
    @GetMapping("/api/vocabulary/other")
    public OtherVocabularyPageResponse listOther(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(required = false) LemmaStatus status,
        @RequestParam(required = false) String pos
    ) {
        int totalMatching = otherVocabularyRepository.countMatching(currentUser.id(), status, pos);
        int totalPages = Math.max(1, (int) Math.ceil(totalMatching / (double) PAGE_SIZE));
        List<OtherVocabularyWordResponse> words =
            otherVocabularyRepository.listPage(currentUser.id(), status, pos, page, PAGE_SIZE);
        return new OtherVocabularyPageResponse(page, totalPages, words);
    }

    /**
     * design.md §19: NEW/LEARNING/KNOWN/IGNORED counts for the statistics
     * page, split the same way the two lists above already are — the top
     * 10k frequency list, and everything else you've actually encountered.
     */
    @GetMapping("/api/vocabulary/stats")
    public VocabularyStatsResponse stats() {
        return new VocabularyStatsResponse(
            statsRepository.topWordCounts(currentUser.id()),
            statsRepository.otherWordCounts(currentUser.id())
        );
    }

    /**
     * Unlike /api/lemmas/{id}/status, this is keyed by term+reading, not a
     * lemma id — a frequency-list word may have no lemma row yet (see
     * VocabularyLemmaResolver). find-or-create then the same status upsert
     * the reader uses.
     */
    @PutMapping("/api/vocabulary/status")
    public ResponseEntity<Void> setStatus(@Valid @RequestBody SetVocabularyStatusRequest request) {
        long lemmaId = lemmaResolver.findOrCreate(request.term(), request.reading());
        statusRepository.setStatus(currentUser.id(), lemmaId, request.status());
        return ResponseEntity.noContent().build();
    }
}
