package com.signedcontract.diff;

import com.signedcontract.model.DiffResult;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 7: DiffEngine Değişimsizlik Invariantı — Validates: Requirements 10.3
 * Property 8: DiffEngine Değişim Tespiti — Validates: Requirements 10.4
 */
class DiffEnginePropertyTest {

    private final DiffEngine engine = new DiffEngine();

    @Property
    void identicalContentNeverFlaggedAsChanged(@ForAll String s) {
        DiffResult result = engine.diff(s, s);
        assertThat(result.changes()).isFalse();
        assertThat(result.result()).isEmpty();
    }

    @Property
    void differingContentAlwaysFlaggedAsChanged(
            @ForAll @StringLength(min = 0, max = 50) String s1,
            @ForAll @StringLength(min = 0, max = 50) String s2) {
        Assume.that(!s1.equals(s2));
        DiffResult result = engine.diff(s1, s2);
        assertThat(result.changes()).isTrue();
    }
}
