package com.logistics.cargorouter.agent;

import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.logistics.cargorouter.component.ShipmentAggregator;
import com.logistics.cargorouter.foundation.RouteCandidate;

/**
 * Ranks alternative routes using a min-heap (PriorityQueue<RouteCandidate>).
 *
 * ─── Walmart heap-optimisation mapping ──────────────────────────────────────
 * Walmart task 4 used a defaultdict to aggregate and then iterated linearly to
 * find the optimal shipment grouping — O(k) scan per group = O(k²) overall.
 *
 * Here, each call to rank() builds a PriorityQueue of RouteCandidate objects.
 * Because RouteCandidate implements Comparable (by compositeScore), the heap
 * maintains the minimum at the root. Fetching the best candidate is O(log k)
 * rather than O(k), which matters when k candidate routes grows large (dense
 * freight graphs with many parallel corridors).
 *
 *   Walmart O(k²)  →  RouteRanker O(k log k)
 *
 * compositeScore = weatherRisk * 0.70 + normalisedDelay * 0.30
 * (weights are configurable in application.yml in a production version)
 * ────────────────────────────────────────────────────────────────────────────
 */
@Component
public class RouteRanker {

    private static final Logger log = LoggerFactory.getLogger(RouteRanker.class);

    /**
     * Delay hours estimated per waypoint hop.
     * A detour through an extra city adds this to the total estimate.
     */
    private static final double HOURS_PER_HOP = 1.5;

    private final RiskAssessor       riskAssessor;
    private final ShipmentAggregator shipmentAggregator;

    public RouteRanker(RiskAssessor riskAssessor, ShipmentAggregator shipmentAggregator) {
        this.riskAssessor = riskAssessor;
        this.shipmentAggregator = shipmentAggregator;
    }

    /**
     * Build a PriorityQueue of all alternative routes and return the one with
     * the lowest compositeScore (safest + fastest trade-off).
     *
     * @param shipmentId   used only for labelling in RouteCandidate
     * @param currentRoute comma-separated string, used to derive alternatives
     * @param baselineHops number of hops on the current route (for delay delta)
     * @return the best alternative, or empty if no alternatives exist
     */
    public Optional<RouteCandidate> rank(String shipmentId, String currentRoute, int baselineHops) {
        List<String[]> alternatives = shipmentAggregator.buildAlternatives(currentRoute);

        if (alternatives.isEmpty()) {
            log.debug("No alternatives found for shipment {}", shipmentId);
            return Optional.empty();
        }

        // Build the min-heap — O(k log k) total for k alternatives
        PriorityQueue<RouteCandidate> heap = new PriorityQueue<>();

        for (String[] waypoints : alternatives) {
            RiskAssessor.AssessmentResult assessment = riskAssessor.score(waypoints);
            double delayDelta = (waypoints.length - baselineHops) * HOURS_PER_HOP;
            RouteCandidate candidate = new RouteCandidate(
                    shipmentId, waypoints, assessment.riskScore(), delayDelta);
            heap.offer(candidate);
            log.debug("Candidate for {}: {} | risk={} score={}",
                    shipmentId, candidate.waypointsAsString(),
                    candidate.getWeatherRisk(), candidate.getCompositeScore());
        }

        // O(log k) — cheapest route surfaces from the min-heap
        RouteCandidate best = heap.poll();
        log.info("Best alternative for {}: {} (score={})",
                shipmentId, best.waypointsAsString(), best.getCompositeScore());
        return Optional.of(best);
    }
}
