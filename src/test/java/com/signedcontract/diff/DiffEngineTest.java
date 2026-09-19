package com.signedcontract.diff;

import com.signedcontract.model.DiffResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiffEngineTest {

    private final DiffEngine engine = new DiffEngine();

    @Test
    void identicalContent_noChanges() {
        DiffResult r = engine.diff("## 1. Taraflar\nAynı metin", "## 1. Taraflar\nAynı metin");
        assertThat(r.changes()).isFalse();
        assertThat(r.result()).isEmpty();
    }

    @Test
    void partialChange_detectsDifference() {
        DiffResult r = engine.diff("**1.200 TL**", "**1.000 TL**");
        assertThat(r.changes()).isTrue();
        assertThat(r.result()).contains("1.000 TL").contains("1.200 TL");
    }

    @Test
    void onlyInSigned_reportedAsChange() {
        DiffResult r = engine.diff("Yeni madde metni", "");
        assertThat(r.changes()).isTrue();
    }

    @Test
    void onlyInOriginal_reportedAsChange() {
        DiffResult r = engine.diff("", "Eski madde metni");
        assertThat(r.changes()).isTrue();
    }

    @Test
    void diffAll_coversUnionOfKeys() {
        Map<String, String> signed = Map.of("1", "A", "2", "B");
        Map<String, String> original = Map.of("1", "A", "3", "C");

        Map<String, DiffResult> results = engine.diffAll(signed, original, 3);

        assertThat(results).containsKeys("1", "2", "3");
        assertThat(results.get("1").changes()).isFalse();
        assertThat(results.get("2").changes()).isTrue();
        assertThat(results.get("3").changes()).isTrue();
    }
}
