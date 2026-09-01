package com.kotanoba.text;

import com.kotanoba.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/texts")
@Validated
public class TextController {

    private static final int PAGE_SIZE = 20;

    private final TextImportService importService;
    private final TextDocumentRepository textDocumentRepository;
    private final TextReadRepository textReadRepository;
    private final TextLibraryRepository textLibraryRepository;
    private final CurrentUser currentUser;

    public TextController(
        TextImportService importService,
        TextDocumentRepository textDocumentRepository,
        TextReadRepository textReadRepository,
        TextLibraryRepository textLibraryRepository,
        CurrentUser currentUser
    ) {
        this.importService = importService;
        this.textDocumentRepository = textDocumentRepository;
        this.textReadRepository = textReadRepository;
        this.textLibraryRepository = textLibraryRepository;
        this.currentUser = currentUser;
    }

    /** decision #3 / #4: tokenizes and persists synchronously, in-request. */
    @PostMapping
    public ResponseEntity<TextSummaryResponse> importText(@Valid @RequestBody ImportTextRequest request) {
        TextDocument text = importService.importText(request.title(), request.text());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(text.getId())
            .toUri();
        return ResponseEntity.created(location).body(TextSummaryResponse.from(text));
    }

    /**
     * Library listing, difficulty-sorted by default (design.md §9d /
     * decision #10), with optional title search (?q=) and sort order
     * (?sort=DIFFICULTY|RECENT). Paginated ahead of an immediate scale need
     * — see TextLibraryRepository's javadoc.
     */
    @GetMapping
    public TextLibraryPageResponse list(
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "DIFFICULTY") TextSortOrder sort,
        @RequestParam(defaultValue = "1") @Min(1) int page
    ) {
        int totalMatching = textLibraryRepository.countForUser(currentUser.id(), q);
        int totalPages = Math.max(1, (int) Math.ceil(totalMatching / (double) PAGE_SIZE));
        List<TextSummaryResponse> texts = textLibraryRepository.listForUser(currentUser.id(), q, sort, page, PAGE_SIZE);
        return new TextLibraryPageResponse(page, totalPages, texts);
    }

    /**
     * The read-path query — design.md decision #5. Ownership is checked via
     * findByIdAndUserId, not a separate authorization step after loading by
     * id: a text belonging to another user must look exactly like a
     * nonexistent one (404, not 403), the same reasoning already applied to
     * login's generic "invalid email or password."
     */
    @GetMapping("/{id}")
    public TextDetailResponse get(@PathVariable long id) {
        TextDocument text = textDocumentRepository.findByIdAndUserId(id, currentUser.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<TokenView> tokens = textReadRepository.loadTokensWithStatus(text.getId(), currentUser.id());
        textDocumentRepository.touchLastOpenedAt(text.getId(), currentUser.id());
        return new TextDetailResponse(text.getId(), text.getTitle(), text.getCreatedAt(), text.getLastReadPosition(), tokens);
    }

    /** Same ownership-scoped 404 semantics as get() above. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        long deleted = textDocumentRepository.deleteByIdAndUserId(id, currentUser.id());
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * design.md §15: saved only when leaving the reader, not on every click
     * — the frontend calls this once, on unmount. Same ownership-scoped 404
     * semantics as delete() above.
     */
    @PutMapping("/{id}/position")
    public ResponseEntity<Void> savePosition(@PathVariable long id, @Valid @RequestBody SaveReadPositionRequest request) {
        int updated = textDocumentRepository.updateLastReadPosition(id, currentUser.id(), request.position());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * design.md §20: rename a text after import — the title import() derives
     * or accepts isn't final. Same ownership-scoped 404 semantics as
     * delete()/savePosition() above. Blank isn't allowed here the way it is
     * on import: a rename is an explicit user action, not a fallback that
     * should silently re-derive a title from the body.
     */
    @PutMapping("/{id}/title")
    public ResponseEntity<Void> updateTitle(@PathVariable long id, @Valid @RequestBody UpdateTextTitleRequest request) {
        int updated = textDocumentRepository.updateTitle(id, currentUser.id(), request.title());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }
}
