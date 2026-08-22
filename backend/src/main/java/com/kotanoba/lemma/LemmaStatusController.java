package com.kotanoba.lemma;

import com.kotanoba.user.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LemmaStatusController {

    private final UserLemmaStatusRepository statusRepository;
    private final CurrentUser currentUser;

    public LemmaStatusController(UserLemmaStatusRepository statusRepository, CurrentUser currentUser) {
        this.statusRepository = statusRepository;
        this.currentUser = currentUser;
    }

    @PutMapping("/api/lemmas/{id}/status")
    public ResponseEntity<Void> setStatus(@PathVariable("id") long lemmaId, @Valid @RequestBody SetLemmaStatusRequest request) {
        statusRepository.setStatus(currentUser.id(), lemmaId, request.status());
        return ResponseEntity.noContent().build();
    }
}
