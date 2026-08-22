package com.kotanoba.text;

import com.kotanoba.user.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/texts")
public class TextController {

    private final TextImportService importService;
    private final TextDocumentRepository textDocumentRepository;
    private final TextReadRepository textReadRepository;
    private final CurrentUser currentUser;

    public TextController(
        TextImportService importService,
        TextDocumentRepository textDocumentRepository,
        TextReadRepository textReadRepository,
        CurrentUser currentUser
    ) {
        this.importService = importService;
        this.textDocumentRepository = textDocumentRepository;
        this.textReadRepository = textReadRepository;
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

    /** Library listing — id/title/created date only; difficulty sort is Slice 3. */
    @GetMapping
    public List<TextSummaryResponse> list() {
        return textDocumentRepository.findByUserIdOrderByCreatedAtDesc(currentUser.id()).stream()
            .map(TextSummaryResponse::from)
            .toList();
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
        return new TextDetailResponse(text.getId(), text.getTitle(), text.getCreatedAt(), tokens);
    }
}
