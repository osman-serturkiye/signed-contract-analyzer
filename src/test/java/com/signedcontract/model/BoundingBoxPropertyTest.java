package com.signedcontract.model;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link BoundingBox#withMargin(int, int, int)}.
 *
 * <p><b>Validates: Requirements 2.7</b>
 *
 * <p>Property 3: BoundingBox Margin Sınır İçi Kalma Invariantı<br>
 * Verifies that applying a margin to any valid bounding box always produces
 * a result that stays within page bounds:
 * <ul>
 *   <li>x_out &ge; 0</li>
 *   <li>y_out &ge; 0</li>
 *   <li>x_out + w_out &le; pageW</li>
 *   <li>y_out + h_out &le; pageH</li>
 * </ul>
 */
class BoundingBoxPropertyTest {

    /**
     * Property 3: BoundingBox Margin Sınır İçi Kalma Invariantı
     *
     * <p><b>Validates: Requirements 2.7</b>
     *
     * <p>For any valid combination of page dimensions (W, H), bounding box
     * coordinates (x, y, w, h) that fit within the page, and a non-negative
     * margin value, {@code withMargin} must always return a bounding box
     * that stays fully within the page boundaries.
     */
    @Property
    void withMarginAlwaysStaysWithinPageBounds(
            @ForAll @IntRange(min = 1, max = 5000) int pageW,
            @ForAll @IntRange(min = 1, max = 5000) int pageH,
            @ForAll @IntRange(min = 0, max = 499) int x,
            @ForAll @IntRange(min = 0, max = 499) int y,
            @ForAll @IntRange(min = 1, max = 500) int w,
            @ForAll @IntRange(min = 1, max = 500) int h,
            @ForAll @IntRange(min = 0, max = 500) int margin
    ) {
        // Constrain x, y, w, h so the bbox fits within the page before margin
        // x in [0, pageW-1], y in [0, pageH-1], x+w <= pageW, y+h <= pageH
        int clampedX = x % pageW;                          // [0, pageW-1]
        int clampedY = y % pageH;                          // [0, pageH-1]
        int clampedW = Math.min(w, pageW - clampedX);      // x + w <= pageW
        int clampedH = Math.min(h, pageH - clampedY);      // y + h <= pageH

        // Ensure width and height are at least 1
        clampedW = Math.max(1, clampedW);
        clampedH = Math.max(1, clampedH);

        BoundingBox bbox = new BoundingBox(clampedX, clampedY, clampedW, clampedH);
        BoundingBox result = bbox.withMargin(margin, pageW, pageH);

        // x_out >= 0
        assertThat(result.x())
                .as("result.x() must be >= 0 for input bbox=%s margin=%d pageW=%d pageH=%d",
                        bbox, margin, pageW, pageH)
                .isGreaterThanOrEqualTo(0);

        // y_out >= 0
        assertThat(result.y())
                .as("result.y() must be >= 0 for input bbox=%s margin=%d pageW=%d pageH=%d",
                        bbox, margin, pageW, pageH)
                .isGreaterThanOrEqualTo(0);

        // x_out + w_out <= pageW
        assertThat(result.x() + result.width())
                .as("result.x() + result.width() must be <= pageW=%d for input bbox=%s margin=%d",
                        pageW, bbox, margin)
                .isLessThanOrEqualTo(pageW);

        // y_out + h_out <= pageH
        assertThat(result.y() + result.height())
                .as("result.y() + result.height() must be <= pageH=%d for input bbox=%s margin=%d",
                        pageH, bbox, margin)
                .isLessThanOrEqualTo(pageH);
    }
}
