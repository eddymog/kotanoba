package com.kotanoba;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Makes claude.md's "Spring Modulith for enforced module boundaries" a
 * checked fact, not just a dependency sitting in pom.xml unused. Each
 * top-level package under com.kotanoba (text, lemma, user, nlp) is one
 * module; this fails the build the moment one of them starts reaching into
 * another's internals or a cycle forms between them, the same way the read
 * path and the schema are protected by tests instead of just good intentions.
 */
class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(KotanobaApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        MODULES.verify();
    }
}
