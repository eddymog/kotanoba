package com.kotanoba.lemma;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LemmaRepository extends JpaRepository<Lemma, Long> {

    List<Lemma> findByIdIn(List<Long> ids);
}
