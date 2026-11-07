package com.hsbc.cmb.hk.dbb.automation.framework.agent.perf;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** nearest-rank 分位数口径的契约测试（T9）。 */
public class PercentilesTest {

    @Test
    public void emptyInputReturnsEmptyRatherThanZero() {
        assertFalse(Percentiles.percentile(List.of(), Percentiles.P95).isPresent());
    }

    @Test
    public void nearestRankTakesRealObservations() {
        List<Long> values = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            values.add(i);
        }
        assertEquals(50L, Percentiles.percentile(values, Percentiles.P50).getAsLong());
        assertEquals(95L, Percentiles.percentile(values, Percentiles.P95).getAsLong());
        assertEquals(1L, Percentiles.percentile(values, 0.0).getAsLong());
        assertEquals(100L, Percentiles.percentile(values, 100.0).getAsLong());
    }

    @Test
    public void singleValueIsAllPercentiles() {
        OptionalLong p95 = Percentiles.percentile(List.of(7L), Percentiles.P95);
        assertTrue(p95.isPresent());
        assertEquals(7L, p95.getAsLong());
    }

    @Test
    public void inputCollectionIsNotModified() {
        List<Long> input = new ArrayList<>(List.of(30L, 10L, 20L));
        assertEquals(30L, Percentiles.percentile(input, Percentiles.P95).getAsLong());
        assertEquals(List.of(30L, 10L, 20L), input);
    }

    @Test
    public void invalidPercentileIsRejected() {
        for (double p : new double[]{-0.1, 100.1, Double.NaN}) {
            try {
                Percentiles.percentile(List.of(1L), p);
                fail("percentile must reject " + p);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("percentile"));
            }
        }
    }

    @Test
    public void nullElementIsRejected() {
        try {
            Percentiles.percentile(Arrays.asList(1L, null, 3L), Percentiles.P50);
            fail("null element must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("null"));
        }
    }
}
