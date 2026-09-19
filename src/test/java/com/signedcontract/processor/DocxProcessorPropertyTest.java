package com.signedcontract.processor;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 6: DOCX Clause Numarası Regex Tespiti — Validates: Requirements 9.2
 * Property 13: Yinelenen Madde Numarası Benzersizlik Garantisi — Validates: Requirements 29.2
 */
class DocxProcessorPropertyTest {

    private final DocxProcessor processor = new DocxProcessor();

    private XWPFParagraph paragraphWithText(String text) {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        return p;
    }

    @Property
    void numericClauseNumberAlwaysDetected(
            @ForAll @IntRange(min = 1, max = 99) int major,
            @ForAll @IntRange(min = 0, max = 99) int minor,
            @ForAll boolean hasMinor) {
        String number = hasMinor ? major + "." + minor : String.valueOf(major);
        XWPFParagraph p = paragraphWithText(number + ". Madde metni burada devam eder");

        String detected = processor.extractClauseNumber(p);

        assertThat(detected).isEqualTo(number);
    }

    @Property
    void maddePrefixAlwaysDetected(
            @ForAll @IntRange(min = 1, max = 99) int major,
            @ForAll("maddeKeyword") String keyword) {
        XWPFParagraph p = paragraphWithText(keyword + " " + major + " başlığı");

        String detected = processor.extractClauseNumber(p);

        assertThat(detected).isEqualTo(String.valueOf(major));
    }

    @Provide
    Arbitrary<String> maddeKeyword() {
        return Arbitraries.of("MADDE", "Madde");
    }

    @Property
    void duplicateClauseNumbersAlwaysGetUniqueKeys(
            @ForAll @IntRange(min = 1, max = 20) int clauseNumber,
            @ForAll @IntRange(min = 2, max = 10) int occurrences) {

        Map<String, Integer> duplicateCount = new HashMap<>();
        String base = String.valueOf(clauseNumber);
        java.util.Set<String> keys = new java.util.HashSet<>();

        for (int i = 0; i < occurrences; i++) {
            String key = processor.resolveDuplicateKey(base, duplicateCount);
            boolean added = keys.add(key);
            assertThat(added).as("Key %s must be unique across occurrences", key).isTrue();
        }

        assertThat(keys).hasSize(occurrences);
        assertThat(keys).contains(base); // first occurrence keeps the bare number
    }
}
