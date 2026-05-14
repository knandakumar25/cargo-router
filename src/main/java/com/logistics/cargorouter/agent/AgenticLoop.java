package com.logistics.cargorouter.agent;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.logistics.cargorouter.entity.ShipmentRecord;
import com.logistics.cargorouter.foundation.RouteCandidate;
import com.logistics.cargorouter.repository.ShipmentRepository;

/**
 * The agentic loop — the autonomous heart of the service.
 *
 * Runs on a fixed schedule and executes five deterministic steps each cycle:
 *
 *   ① Monitor  — load all ACTIVE shipments from the database
 *   ② Assess   — score the current route's weather risk (RiskAssessor)
 *   ③ Predict  — rank alternative routes via min-heap (RouteRanker)
 *   ④ Act      — publish RerouteCommand + persist audit if improvement > threshold
 *   ⑤ Repeat   — sleep until next scheduled tick
 *
 * Design principles from JPMC Midas:
 *   - Each step is a single-concern collaborator bean (same as JPMC's
 *     IncentiveService / UserRepository separation of concerns).
 *   - Every action that changes state is persisted (TransactionRecord pattern
 *     → RouteDecision pattern).
 *
 * Design principles from Walmart task 4:
 *   - Data is aggregated in one place (ShipmentAggregator) before being
 *     handed to the decision layer (RouteRanker).
 *   - The heap in RouteRanker keeps ranking O(k log k) instead of O(k²).
 */
@Component
public class AgenticLoop {

    private static final Logger log = LoggerFactory.getLogger(AgenticLoop.class);

    private final ShipmentRepository shipmentRepository;
    private final RiskAssessor       riskAssessor;
    private final RouteRanker        routeRanker;
    private final RerouteDecider     rerouteDecider;

    @Value("${general.risk-threshold:0.65}")
    private double riskThreshold;

    public AgenticLoop(ShipmentRepository shipmentRepository,
                       RiskAssessor riskAssessor,
                       RouteRanker routeRanker,
                       RerouteDecider rerouteDecider) {
        this.shipmentRepository = shipmentRepository;
        this.riskAssessor       = riskAssessor;
        this.routeRanker        = routeRanker;
        this.rerouteDecider     = rerouteDecider;
    }

    /**
     * One full agent cycle. Runs every {@code general.agent-interval-ms} milliseconds
     * (default 60 000 ms = 1 minute).
     *
     * fixedDelayString means the next cycle starts only after the current one
     * completes — this prevents overlapping cycles if a cycle takes > 1 minute.
     */
    @Scheduled(fixedDelayString = "${general.agent-interval-ms:60000}")
    public void cycle() {
        // ① Monitor
        List<ShipmentRecord> activeShipments = shipmentRepository.findByStatus("ACTIVE");
        activeShipments.addAll(shipmentRepository.findByStatus("REROUTED"));

        if (activeShipments.isEmpty()) {
            log.debug("Agent cycle: no active shipments to evaluate.");
            return;
        }

        log.info("Agent cycle started — evaluating {} shipment(s)", activeShipments.size());

        for (ShipmentRecord shipment : activeShipments) {
            evaluateShipment(shipment);
        }

        log.info("Agent cycle complete.");
    }

    private void evaluateShipment(ShipmentRecord shipment) {
        String currentRoute = shipment.getCurrentRoute();
        if (currentRoute == null || currentRoute.isBlank()) {
            log.warn("Shipment {} has no current route — skipping", shipment.getShipmentId());
            return;
        }

        String[] waypoints = currentRoute.split(",");

        // ② Assess
        RiskAssessor.AssessmentResult assessment = riskAssessor.score(waypoints);
        double currentRisk = assessment.riskScore();
        log.info("Shipment {} — current route risk: {} ({})",
                shipment.getShipmentId(), currentRisk, assessment.trigger());

        if (currentRisk < riskThreshold) {
            log.debug("Shipment {} risk {} below threshold {} — no action",
                    shipment.getShipmentId(), currentRisk, riskThreshold);
            return;
        }

        log.warn("Shipment {} exceeds risk threshold ({} >= {}) — seeking alternatives",
                shipment.getShipmentId(), currentRisk, riskThreshold);

        // ③ Predict — rank alternatives via min-heap
        Optional<RouteCandidate> bestAlternative =
                routeRanker.rank(shipment.getShipmentId(), currentRoute, waypoints.length);

        if (bestAlternative.isEmpty()) {
            log.warn("No alternative routes found for shipment {} — cannot reroute",
                    shipment.getShipmentId());
            return;
        }

        // ④ Act
        rerouteDecider.act(shipment, bestAlternative.get(), currentRisk, assessment.trigger());
    }
}
