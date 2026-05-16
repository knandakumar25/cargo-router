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
 * compositeScore = weatherRisk * 0.70 + normalisedDelay * 0.30; O(k log k).
 */
@Component
public class RouteRanker {

    private static final Logger log = LoggerFactory.getLogger(RouteRanker.class);

    private static final double HOURS_PER_HOP = 1.5; // estimated travel time per extra hop

    private final RiskAssessor       riskAssessor;
    private final ShipmentAggregator shipmentAggregator;

    public RouteRanker(RiskAssessor riskAssessor, ShipmentAggregator shipmentAggregator) {
        this.riskAssessor = riskAssessor;
        this.shipmentAggregator = shipmentAggregator;
    }

    /**
     * Builds a min-heap of all alternative routes and returns the lowest-scoring one.
     *
     * @param shipmentId   used for logging and labelling route candidates
     * @param currentRoute comma-separated waypoint string; source of alternatives
     * @param baselineHops hop count on the current route, used to compute delay delta
     * @return the best alternative, or empty if no alternatives exist
     */
    public Optional<RouteCandidate> rank(String shipmentId, String currentRoute, int baselineHops) {
        List<String[]> alternatives = shipmentAggregator.buildAlternatives(currentRoute);

        if (alternatives.isEmpty()) {
            log.debug("No alternatives found for shipment {}", shipmentId);
            return Optional.empty();
        }

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

        RouteCandidate best = heap.poll();
        log.info("Best alternative for {}: {} (score={})",
                shipmentId, best.waypointsAsString(), best.getCompositeScore());
        return Optional.of(best);
    }
}
