package com.signedcontract.report;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 9: Analiz Raporu Doğal Madde Sıralaması
 * Validates: Requirements 12.1, 12.4
 */
class ReportAssemblerPropertyTest {

    @Property
    void naturalOrderingSortsNumericClauseKeysCorrectly(
            @ForAll @IntRange(min = 1, max = 15) int major,
            @ForAll @IntRange(min = 1, max = 15) int minor) {

        List<String> keys = new ArrayList<>(List.of(
                "1", "1.1", "1.2", "2", "10", major + "." + minor));
        keys.sort(new ReportAssembler.NaturalClauseKeyComparator());

        // "2" must always sort before "10" (fails under naive lexicographic order)
        int idx2 = keys.indexOf("2");
        int idx10 = keys.indexOf("10");
        assertThat(idx2).isLessThan(idx10);

        // "1.1" must sort before "1.2"
        assertThat(keys.indexOf("1.1")).isLessThan(keys.indexOf("1.2"));

        // "1" must sort before "1.1"
        assertThat(keys.indexOf("1")).isLessThan(keys.indexOf("1.1"));
    }

    @Property
    void duplicateSuffixSortsImmediatelyAfterBaseKey(@ForAll @IntRange(min = 1, max = 9) int dupIndex) {
        List<String> keys = new ArrayList<>(List.of("5", "5-duplicate-" + dupIndex, "6"));
        keys.sort(new ReportAssembler.NaturalClauseKeyComparator());

        assertThat(keys).containsExactly("5", "5-duplicate-" + dupIndex, "6");
    }
}
