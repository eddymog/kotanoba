package com.kotanoba.text;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TextDocumentRepository extends JpaRepository<TextDocument, Long> {

    // Scoped by owner, not just id — the same isolation already verified for
    // the list and import endpoints. A text belonging to another user must
    // be indistinguishable from a nonexistent one (404, not 403), so nothing
    // here confirms whether the id exists at all.
    Optional<TextDocument> findByIdAndUserId(Long id, Long userId);

    // Same ownership scoping as above, for delete. Returns the number of
    // rows deleted (0 or 1) so the controller can tell "deleted" from
    // "nothing to delete / not yours" without a separate existence check.
    // text_token cascades via its FK (V1__initial_schema.sql), so this is
    // the only statement needed. Derived deleteBy methods load then
    // entityManager.remove() each match, which — caught by actually running
    // this, not by inspection — throws TransactionRequiredException without
    // a transaction of its own, the same reason touchLastOpenedAt needs one.
    @Transactional
    long deleteByIdAndUserId(Long id, Long userId);

    // Plain JPA for ordinary CRUD (claude.md) — this is a one-column update
    // on the read path's "open a text" action, not a hot loop, so it doesn't
    // need JdbcTemplate's ceremony. @Modifying queries need their own
    // transaction if the caller isn't already in one.
    @Modifying
    @Transactional
    @Query("UPDATE TextDocument t SET t.lastOpenedAt = CURRENT_TIMESTAMP WHERE t.id = :id AND t.userId = :userId")
    void touchLastOpenedAt(@Param("id") Long id, @Param("userId") Long userId);

    // Returns rows affected (0 or 1), same ownership-scoped-404 pattern as
    // deleteByIdAndUserId — this is a separate endpoint (PUT
    // /api/texts/{id}/position), not something get() already checked
    // ownership for.
    @Modifying
    @Transactional
    @Query("UPDATE TextDocument t SET t.lastReadPosition = :position WHERE t.id = :id AND t.userId = :userId")
    int updateLastReadPosition(@Param("id") Long id, @Param("userId") Long userId, @Param("position") int position);

    // Same ownership-scoped-404 pattern as updateLastReadPosition above —
    // renaming a text after import (design.md §20).
    @Modifying
    @Transactional
    @Query("UPDATE TextDocument t SET t.title = :title WHERE t.id = :id AND t.userId = :userId")
    int updateTitle(@Param("id") Long id, @Param("userId") Long userId, @Param("title") String title);
}
