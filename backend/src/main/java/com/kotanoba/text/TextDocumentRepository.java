package com.kotanoba.text;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TextDocumentRepository extends JpaRepository<TextDocument, Long> {

    List<TextDocument> findByUserIdOrderByCreatedAtDesc(Long userId);

    // Scoped by owner, not just id — the same isolation already verified for
    // the list and import endpoints. A text belonging to another user must
    // be indistinguishable from a nonexistent one (404, not 403), so nothing
    // here confirms whether the id exists at all.
    Optional<TextDocument> findByIdAndUserId(Long id, Long userId);
}
