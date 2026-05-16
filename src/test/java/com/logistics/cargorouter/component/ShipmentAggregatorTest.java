package com.logistics.cargorouter.component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.logistics.cargorouter.repository.ShipmentRepository;

/**
 * Unit tests for ShipmentAggregator.
 *
 * ShipmentAggregator is pure logic (no JPA dependencies), so it is
 * instantiated directly. ShipmentRepository is declared as a @Mock to
 * demonstrate H2 isolation — no Spring context is loaded and the in-memory
 * database is never started during this test class.
 */
@ExtendWith(MockitoExtension.class)
class ShipmentAggregatorTest {

    /**
     * Declared to confirm H2 isolation: Mockito replaces the real bean,
     * so no database connections are opened for any test in this class.
     */
    @Mock
    @SuppressWarnings("unused") // declared for H2 isolation documentation; Mockito wires it via annotation
    ShipmentRepository shipmentRepository;

    private ShipmentAggregator aggregator;

    @BeforeEach
    @SuppressWarnings("unused") // invoked by JUnit 5 via @BeforeEach reflection
    void setUp() {
        aggregator = new ShipmentAggregator();
    }

    // ─── buildAlternatives: empty / single-waypoint inputs ──────────────────

    /**
     * Edge case 1 — empty waypoint list.
     *
     * A comma-split of "", "Chicago", or " " produces fewer than 2 parts.
     * buildAlternatives must guard this and return an empty list — not throw
     * and not produce a single-element array that would confuse RouteRanker.
     */
    @ParameterizedTest(name = "[{index}] route=\"{0}\" → empty alternatives")
    @ValueSource(strings = {"", "Chicago", " "})
    void buildAlternatives_emptyOrSingleWaypoint_returnsEmptyList(String route) {
        List<String[]> result = aggregator.buildAlternatives(route);
        assertThat(result).isEmpty();
    }

    /**
     * A valid two-city route must yield at least one alternative
     * (the segment graph has neighbours for every major hub).
     */
    @Test
    void buildAlternatives_validRoute_returnsNonEmptyList() {
        List<String[]> result = aggregator.buildAlternatives("Chicago,Nashville");
        assertThat(result).isNotEmpty();
    }

    /**
     * Alternative routes must never re-use the current first hop.
     * If the active route goes Chicago → Indianapolis, every alternative
     * must leave Chicago via a different city.
     */
    @Test
    void buildAlternatives_doesNotRepeatCurrentFirstHop() {
        String route = "Chicago,Indianapolis,Louisville,Nashville";
        List<String[]> alternatives = aggregator.buildAlternatives(route);

        assertThat(alternatives).isNotEmpty();
        alternatives.forEach(alt ->
                assertThat(alt[1]).isNotEqualTo("Indianapolis"));
    }

    /**
     * Every alternative must share the same origin and destination as the
     * input route (rerouting never changes where the truck started or
     * where it is going).
     */
    @Test
    void buildAlternatives_allCandidatesShareOriginAndDestination() {
        String route = "Chicago,Indianapolis,Louisville,Nashville";
        String[] parts = route.split(",");
        String expectedOrigin = parts[0];
        String expectedDest   = parts[parts.length - 1];

        List<String[]> alternatives = aggregator.buildAlternatives(route);
        alternatives.forEach(alt -> {
            assertThat(alt[0]).isEqualTo(expectedOrigin);
            assertThat(alt[alt.length - 1]).isEqualTo(expectedDest);
        });
    }

    // ─── resolveCity: data-corruption / null-origin scenarios ────────────────

    /**
     * Edge case 3 — CSV 'origin' field is null or blank (data corruption).
     *
     * A null or empty locationId must never throw NullPointerException.
     * The method must return the safe-default city ("Chicago") so that
     * downstream processing can continue rather than crashing the consumer thread.
     */
    @ParameterizedTest(name = "[{index}] locationId=<{0}> → safe default \"Chicago\"")
    @NullAndEmptySource
    void resolveCity_nullOrEmptyId_returnsSafeDefault(String locationId) {
        assertThat(aggregator.resolveCity(locationId)).isEqualTo("Chicago");
    }

    /**
     * Short strings (< 6 characters) cannot form a valid UUID prefix
     * and must also fall back to the safe default.
     */
    @ParameterizedTest(name = "[{index}] short id=\"{0}\" → safe default \"Chicago\"")
    @ValueSource(strings = {"a", "ab", "abc", "abcd", "abcde"})
    void resolveCity_shortId_returnsSafeDefault(String locationId) {
        assertThat(aggregator.resolveCity(locationId)).isEqualTo("Chicago");
    }

    /**
     * IDs from Walmart's shipping_data_0.csv must resolve to their
     * correct hub cities via the WAYPOINT_CATALOG prefix lookup.
     */
    @ParameterizedTest(name = "[{index}] id prefix \"{0}\" → \"{1}\"")
    @CsvSource({
            "d5566b15-b071-4acf-8e8e-c98433083b2d, Chicago",
            "c42f0de8-b4f0-4167-abd1-ae79e5e18eea, Dallas",
            "b145f396-de9b-42f1-9cc9-f5b52c3a941c, Atlanta",
            "f4372224-759f-43b3-bc83-ca6106bba1af, Los Angeles",
            "50d33715-4c77-4dd9-8b9d-ff1ca372a2a2, Nashville",
            "172eb8f3-1033-4fb6-b66b-d0df09df3161, Houston",
            "65e4544d-42ae-4751-9580-bdcb90e5fcda, Denver",
            "745bee4e-710c-4538-8df1-5c146e1092a6, Philadelphia"
    })
    void resolveCity_knownCatalogId_returnsMappedCity(String locationId, String expectedCity) {
        assertThat(aggregator.resolveCity(locationId.trim())).isEqualTo(expectedCity.trim());
    }

    // ─── buildInitialRoute: null / corrupted inputs ──────────────────────────

    /**
     * Edge case 3 (continued) — null origin in buildInitialRoute.
     *
     * When a Kafka message arrives with a null originWarehouse (corrupted CSV
     * upstream), buildInitialRoute must not throw. It should fall back to the
     * safe default city and still produce a usable (if imperfect) route string.
     */
    @ParameterizedTest(name = "[{index}] originId={0}, destId={1} → no exception")
    @CsvSource({
            ", 50d33715-4c77-4dd9-8b9d-ff1ca372a2a2",
            "d5566b15-b071-4acf-8e8e-c98433083b2d, ",
            ", "
    })
    void buildInitialRoute_nullOrEmptyFields_doesNotThrow(String originId, String destId) {
        // trim() converts the CsvSource empty-string tokens back to ""
        String o = (originId == null) ? null : originId.trim();
        String d = (destId   == null) ? null : destId.trim();
        assertThatNoException().isThrownBy(() -> aggregator.buildInitialRoute(o, d));
    }

    /**
     * For known catalog endpoints, the route string must start at the resolved
     * origin city and end at the resolved destination city.
     */
    @ParameterizedTest(name = "[{index}] {0} → {1} : route starts={2}, ends={3}")
    @CsvSource({
            "d5566b15-b071-4acf-8e8e-c98433083b2d, 50d33715-4c77-4dd9-8b9d-ff1ca372a2a2, Chicago,   Nashville",
            "c42f0de8-b4f0-4167-abd1-ae79e5e18eea, 172eb8f3-1033-4fb6-b66b-d0df09df3161, Dallas,    Houston"
    })
    void buildInitialRoute_knownEndpoints_routeStartsAndEndsCorrectly(
            String originId, String destId, String expectedFirst, String expectedLast) {
        String route  = aggregator.buildInitialRoute(originId.trim(), destId.trim());
        String[] hops = route.split(",");
        assertThat(hops[0].trim()).isEqualTo(expectedFirst.trim());
        assertThat(hops[hops.length - 1].trim()).isEqualTo(expectedLast.trim());
    }

    /**
     * The returned route must always contain at least two cities
     * (origin and destination).
     */
    @Test
    void buildInitialRoute_alwaysAtLeastTwoHops() {
        String route = aggregator.buildInitialRoute(
                "d5566b15-b071-4acf-8e8e-c98433083b2d",
                "50d33715-4c77-4dd9-8b9d-ff1ca372a2a2");
        assertThat(route.split(",")).hasSizeGreaterThanOrEqualTo(2);
    }
}
