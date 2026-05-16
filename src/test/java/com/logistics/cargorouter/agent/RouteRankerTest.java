package com.logistics.cargorouter.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.logistics.cargorouter.component.ShipmentAggregator;
import com.logistics.cargorouter.foundation.RouteCandidate;
import com.logistics.cargorouter.repository.ShipmentRepository;

/**
 * Unit tests for RouteRanker.
 *
 * Both ShipmentAggregator and RiskAssessor are mocked so that:
 *  - No graph traversal happens (aggregator is controlled)
 *  - No HTTP calls to the weather API are made (riskAssessor is controlled)
 *  - H2 is never started (ShipmentRepository mock prevents any JPA wiring)
 */
@ExtendWith(MockitoExtension.class)
class RouteRankerTest {

    /**
     * Mocked to prevent H2 from starting. AgenticLoop (which uses RouteRanker)
     * depends on ShipmentRepository; mocking it here documents that these
     * unit tests are fully isolated from the database layer.
     */
    @Mock
    @SuppressWarnings("unused") // declared for H2 isolation documentation; Mockito wires it via annotation
    ShipmentRepository shipmentRepository;

    @Mock
    RiskAssessor riskAssessor;

    @Mock
    ShipmentAggregator shipmentAggregator;

    private RouteRanker routeRanker;

    @BeforeEach
    @SuppressWarnings("unused") // invoked by JUnit 5 via @BeforeEach reflection
    void setUp() {
        routeRanker = new RouteRanker(riskAssessor, shipmentAggregator);
    }

    // ─── Empty / single-waypoint inputs ─────────────────────────────────────

    /**
     * Edge case 1 — empty waypoint list.
     *
     * When the current route string has fewer than two comma-separated parts,
     * ShipmentAggregator.buildAlternatives() returns an empty list.
     * RouteRanker must propagate this as Optional.empty() — no NPE, no
     * heap construction attempted.
     */
    @ParameterizedTest(name = "[{index}] route=\"{0}\" → Optional.empty()")
    @ValueSource(strings = {"", "Chicago", " "})
    void rank_emptyOrSingleWaypoint_returnsEmptyOptional(String route) {
        when(shipmentAggregator.buildAlternatives(route)).thenReturn(List.of());

        Optional<RouteCandidate> result = routeRanker.rank("SHP-EMPTY", route, 1);

        assertThat(result).isEmpty();
    }

    // ─── Power-of-Two Max Heap: identical risk scores ────────────────────────

    /**
     * Edge case 2 — Power-of-Two heap with all candidates at identical risk.
     *
     * Java's PriorityQueue is a binary min-heap whose internal array resizes in
     * powers of two (initial capacity 11, grows to 2^n). This test drives
     * exactly 1, 2, 4, and 8 candidates (2^0 … 2^3) through the heap to verify:
     *
     *   a) The heap correctly surfaces the globally minimum compositeScore
     *      even when all weatherRisk values are identical (0.5).
     *   b) Tie-breaking falls to the delay component (normalised hop delta).
     *      The candidate with the fewest extra hops beyond baselineHops wins.
     *   c) The invariant holds at every power-of-two boundary, ruling out
     *      off-by-one errors in heap promotion/demotion during sift-down.
     *
     * Candidate design:
     *   - All have weatherRisk = SHARED_RISK (0.5)
     *   - Waypoint counts vary: [baseline-1, baseline, baseline+1, baseline+2 …]
     *   - compositeScore = 0.5*0.70 + normalise(delayDelta)*0.30
     *   - Routes with length <= baselineHops → normalise(negative delta) = 0 → score = MIN_SCORE
     *   - Routes with length > baselineHops  → score > MIN_SCORE
     *   → heap.poll() must always return a candidate whose score == MIN_SCORE
     */
    @ParameterizedTest(name = "[{index}] {0} candidate(s) (2^{1}), risk={2} → heap pops min score")
    @MethodSource("powerOfTwoHeapArguments")
    void rank_powerOfTwoCandidates_identicalRisk_heapPopsMinScore(
            List<String[]> candidates, int exponent, double sharedRisk, int baselineHops) {

        when(shipmentAggregator.buildAlternatives(anyString())).thenReturn(candidates);
        when(riskAssessor.score(any(String[].class)))
                .thenReturn(new RiskAssessor.AssessmentResult(sharedRisk, "WEATHER_CLEAR@TEST"));

        Optional<RouteCandidate> best = routeRanker.rank("SHP-HEAP-2E" + exponent, "X,Y,Z", baselineHops);

        assertThat(best).isPresent();

        // Compute the minimum compositeScore across all candidates independently
        double minExpected = candidates.stream()
                .mapToDouble(wp -> computeCompositeScore(sharedRisk, wp.length, baselineHops))
                .min()
                .orElseThrow();

        assertThat(best.get().getCompositeScore())
                .as("heap.poll() must return the globally minimum compositeScore")
                .isCloseTo(minExpected, within(1e-9));

        // The returned candidate's weatherRisk must equal the shared risk
        assertThat(best.get().getWeatherRisk())
                .isCloseTo(sharedRisk, within(1e-9));
    }

    /**
     * Provides (candidates, exponent, sharedRisk, baselineHops) for each
     * power-of-two boundary.
     *
     * Candidate waypoint counts intentionally span both sides of baselineHops (3)
     * so the normalisation logic is exercised:
     *   length < 3 → delayDelta < 0 → normalised = 0   → score = MIN_SCORE
     *   length = 3 → delayDelta = 0 → normalised = 0   → score = MIN_SCORE
     *   length > 3 → delayDelta > 0 → normalised > 0   → score > MIN_SCORE
     */
    @SuppressWarnings("unused") // referenced via @MethodSource("powerOfTwoHeapArguments") — JDT can't resolve annotation string references
    static Stream<Arguments> powerOfTwoHeapArguments() {
        int baseline = 3;
        double risk  = 0.5;

        // 2^0 = 1 candidate: shorter than baseline → score = MIN_SCORE
        List<String[]> one = List.<String[]>of(
                route("A", "B")                                     // length 2 < 3
        );

        // 2^1 = 2 candidates: one at MIN_SCORE, one above
        List<String[]> two = List.<String[]>of(
                route("A", "B"),                                    // length 2 → MIN_SCORE
                route("A", "B", "C", "D")                          // length 4 → score > MIN
        );

        // 2^2 = 4 candidates: two at MIN_SCORE, two above
        List<String[]> four = List.<String[]>of(
                route("A", "B"),                                    // length 2 → MIN_SCORE
                route("A", "B", "C"),                              // length 3 → MIN_SCORE
                route("A", "B", "C", "D"),                         // length 4 → above
                route("A", "B", "C", "D", "E")                    // length 5 → above
        );

        // 2^3 = 8 candidates: covers full heap-level promotion across three tree levels
        List<String[]> eight = new ArrayList<>();
        eight.add(route("A", "B"));                                // 2 → MIN_SCORE
        eight.add(route("A", "B", "C"));                           // 3 → MIN_SCORE
        eight.add(route("A", "B", "C", "D"));                     // 4 → above
        eight.add(route("A", "B", "C", "D", "E"));                // 5 → above
        eight.add(route("A", "B", "C", "D", "E", "F"));           // 6 → above
        eight.add(route("A", "B", "C", "D", "E", "F", "G"));      // 7 → above
        eight.add(route("A", "B", "C", "D", "E", "F", "G", "H")); // 8 → above
        eight.add(route("A", "B", "C", "D", "E", "F", "G", "H", "I")); // 9 → above

        return Stream.of(
                Arguments.of(one,   0, risk, baseline),
                Arguments.of(two,   1, risk, baseline),
                Arguments.of(four,  2, risk, baseline),
                Arguments.of(eight, 3, risk, baseline)
        );
    }

    // ─── Risk variation: heap returns the lowest-risk candidate ─────────────

    /**
     * When candidates have different risk scores, the heap must return the
     * candidate with the overall minimum compositeScore — not just the one
     * inserted first or last.
     */
    @Test
    void rank_varyingRiskCandidates_returnsLowestCompositeScore() {
        int baseline = 3;
        List<String[]> candidates = List.of(
                route("A", "B", "C"),           // risk will be 0.9 (storm)
                route("A", "D", "E"),           // risk will be 0.1 (clear)  ← winner
                route("A", "F", "G", "H")       // risk will be 0.5 (rain)
        );

        when(shipmentAggregator.buildAlternatives(anyString())).thenReturn(candidates);
        when(riskAssessor.score(candidates.get(0)))
                .thenReturn(new RiskAssessor.AssessmentResult(0.9, "WEATHER_THUNDERSTORM@A"));
        when(riskAssessor.score(candidates.get(1)))
                .thenReturn(new RiskAssessor.AssessmentResult(0.1, "WEATHER_CLEAR@A"));
        when(riskAssessor.score(candidates.get(2)))
                .thenReturn(new RiskAssessor.AssessmentResult(0.5, "WEATHER_RAIN@A"));

        Optional<RouteCandidate> best = routeRanker.rank("SHP-RISK", "X,Y,Z", baseline);

        assertThat(best).isPresent();
        assertThat(best.get().getWeatherRisk())
                .as("lowest-risk candidate (0.1) must surface from heap")
                .isCloseTo(0.1, within(1e-9));
    }

    /**
     * When a single alternative exists, rank() must return it regardless of
     * its risk score (no comparison needed, heap trivially pops it).
     */
    @Test
    void rank_singleAlternative_alwaysReturnsIt() {
        String[] onlyRoute = route("A", "B", "C");
        when(shipmentAggregator.buildAlternatives(anyString())).thenReturn(List.<String[]>of(onlyRoute));
        when(riskAssessor.score(onlyRoute))
                .thenReturn(new RiskAssessor.AssessmentResult(0.75, "WEATHER_SNOW@B"));

        Optional<RouteCandidate> best = routeRanker.rank("SHP-SOLO", "X,Y,Z", 3);

        assertThat(best).isPresent();
        assertThat(best.get().getWaypoints()).isEqualTo(onlyRoute);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** Mirror of RouteCandidate's compositeScore formula for assertion maths. */
    private static double computeCompositeScore(double risk, int waypointCount, int baselineHops) {
        double delayHrs    = (waypointCount - baselineHops) * 1.5;   // HOURS_PER_HOP = 1.5
        double normalised  = Math.max(0.0, Math.min(delayHrs, 24.0)) / 24.0;
        return (risk * 0.70) + (normalised * 0.30);
    }

    /** Convenience factory so test data declarations stay readable. */
    private static String[] route(String... waypoints) {
        return waypoints;
    }
}
