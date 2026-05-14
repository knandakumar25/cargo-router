package com.logistics.cargorouter.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.logistics.cargorouter.entity.RouteDecision;
import com.logistics.cargorouter.entity.ShipmentRecord;
import com.logistics.cargorouter.foundation.RerouteCommand;
import com.logistics.cargorouter.foundation.RouteCandidate;
import com.logistics.cargorouter.repository.RouteDecisionRepository;
import com.logistics.cargorouter.repository.ShipmentRepository;

/**
 * Acts on a routing decision: publishes a RerouteCommand to Kafka, updates the
 * ShipmentRecord, and persists a RouteDecision for audit.
 *
 * Only acts when the alternative's compositeScore is meaningfully better than
 * the current route's risk (controlled by general.min-improvement-threshold).
 * This prevents churn — repeatedly re-routing when the difference is marginal.
 */
@Component
public class RerouteDecider {

    private static final Logger log = LoggerFactory.getLogger(RerouteDecider.class);

    private final KafkaTemplate<String, RerouteCommand> kafkaTemplate;
    private final ShipmentRepository                    shipmentRepository;
    private final RouteDecisionRepository               decisionRepository;

    @Value("${general.kafka-outbound-topic}")
    private String rerouteTopic;

    @Value("${general.min-improvement-threshold:0.10}")
    private double minImprovementThreshold;

    public RerouteDecider(KafkaTemplate<String, RerouteCommand> kafkaTemplate,
                          ShipmentRepository shipmentRepository,
                          RouteDecisionRepository decisionRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.shipmentRepository = shipmentRepository;
        this.decisionRepository = decisionRepository;
    }

    /**
     * Evaluate whether to commit the reroute and, if so, take all three actions:
     *  1. Publish RerouteCommand to Kafka (fleet systems consume this)
     *  2. Update ShipmentRecord.currentRoute in the database
     *  3. Persist RouteDecision audit record
     *
     * @param shipment       the active shipment under review
     * @param best           the best alternative from RouteRanker's heap
     * @param currentRisk    the current route's assessed risk score
     * @param trigger        human-readable trigger label from RiskAssessor
     */
    public void act(ShipmentRecord shipment, RouteCandidate best,
                    double currentRisk, String trigger) {

        double improvement = currentRisk - best.getWeatherRisk();
        if (improvement < minImprovementThreshold) {
            log.info("Reroute skipped for {} — improvement {} below threshold {}",
                    shipment.getShipmentId(), improvement, minImprovementThreshold);
            return;
        }

        String previousRoute = shipment.getCurrentRoute();
        String newRoute      = best.waypointsAsString().replace(" → ", ",");

        log.info("Rerouting {} | {} → {} | riskDelta={}",
                shipment.getShipmentId(), previousRoute, newRoute, -improvement);

        // 1. Publish to Kafka outbound topic
        RerouteCommand command = new RerouteCommand(
                shipment.getShipmentId(),
                shipment.getDriverId(),
                previousRoute,
                newRoute,
                best.getWeatherRisk(),
                trigger
        );
        kafkaTemplate.send(rerouteTopic, shipment.getShipmentId(), command);

        // 2. Update ShipmentRecord (currentRoute + status)
        shipment.setCurrentRoute(newRoute);
        shipment.setStatus("REROUTED");
        shipmentRepository.save(shipment);

        // 3. Persist audit record
        RouteDecision decision = new RouteDecision(
                shipment.getShipmentId(),
                shipment.getDriverId(),
                previousRoute,
                newRoute,
                currentRisk,
                best.getWeatherRisk(),
                trigger
        );
        decisionRepository.save(decision);
    }
}
