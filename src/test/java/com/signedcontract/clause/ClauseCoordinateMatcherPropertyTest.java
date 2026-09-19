package com.signedcontract.clause;

import com.signedcontract.model.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 4: ClauseCoordinateMatcher Tekil Atama Invariantı
 * Validates: Requirements 5.1
 */
class ClauseCoordinateMatcherPropertyTest {

    @Property
    void everyBlockAssignedToAtMostOneClause(
            @ForAll @IntRange(min = 1, max = 6) int clauseCount,
            @ForAll @IntRange(min = 1, max = 5) int linesPerClause) {

        // Build a single synthetic page whose blocks alternate a clause-start
        // block followed by `linesPerClause` plain content blocks.
        List<OcrBlock> blocks = new ArrayList<>();
        int y = 0;
        for (int c = 1; c <= clauseCount; c++) {
            blocks.add(new OcrBlock("title", new BoundingBox(0, y, 500, 20),
                    c + ". Madde başlığı", null));
            y += 20;
            for (int l = 0; l < linesPerClause; l++) {
                blocks.add(new OcrBlock("text", new BoundingBox(0, y, 500, 20),
                        "içerik satırı " + l, null));
                y += 20;
            }
        }

        PageOcrResult page = new PageOcrResult(1, blocks, 500, y);
        ClauseCoordinateMatcher matcher = new ClauseCoordinateMatcher(null);
        List<ClauseMapping> mappings = matcher.map(List.of(page), null);

        // Every OcrBlock instance must appear in exactly one mapping's block list.
        Map<OcrBlock, Integer> occurrenceCount = new IdentityHashMap<>();
        for (ClauseMapping m : mappings) {
            for (OcrBlock b : m.blocks()) {
                occurrenceCount.merge(b, 1, Integer::sum);
            }
        }

        assertThat(occurrenceCount.values()).allMatch(count -> count == 1);
        // Total assigned blocks must not exceed total input blocks.
        int totalAssigned = occurrenceCount.size();
        assertThat(totalAssigned).isLessThanOrEqualTo(blocks.size());
    }
}
