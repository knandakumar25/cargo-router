package com.logistics.cargorouter.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.logistics.cargorouter.entity.ShipmentRecord;
import com.logistics.cargorouter.foundation.CargoShipment;
import com.logistics.cargorouter.repository.ShipmentRepository;

/**
 * Kafka consumer for inbound cargo events.
 *
 * Structurally identical to JPMC's TransactionListener:
 *   @KafkaListener → validate → enrich (build initial route) → persist
 *
 * The initial route is constructed by ShipmentAggregator, which replicates
 * Walmart's multi-source join pattern (products + metadata → complete record).
 */
@Component
public class ShipmentListener {

    private static final Logger log = LoggerFactory.getLogger(ShipmentListener.class);

    private final ShipmentRepository    shipmentRepository;
    private final ShipmentAggregator    shipmentAggregator;

    public ShipmentListener(ShipmentRepository shipmentRepository,
                             ShipmentAggregator shipmentAggregator) {
        this.shipmentRepository = shipmentRepository;
        this.shipmentAggregator = shipmentAggregator;
    }

    /**
     * Consume a cargo shipment event from the inbound Kafka topic.
     *
     * Guards:
     *  - Duplicate shipmentId → skip (idempotent; Kafka at-least-once delivery)
     *  - Missing origin or destination → skip
     */
    @KafkaListener(topics = "${general.kafka-inbound-topic}")
    public void listen(CargoShipment shipment) {
        log.info("Received shipment event: {}", shipment);

        if (shipment.getShipmentId() == null || shipment.getShipmentId().isBlank()) {
            log.warn("Dropping shipment with null/empty ID");
            return;
        }

        if (shipment.getOriginWarehouse() == null || shipment.getDestinationStore() == null) {
            log.warn("Dropping shipment {} — missing origin or destination", shipment.getShipmentId());
            return;
        }

        // Idempotency: skip if already persisted
        if (shipmentRepository.existsById(shipment.getShipmentId())) {
            log.debug("Shipment {} already exists — skipping duplicate event", shipment.getShipmentId());
            return;
        }

        // Build the initial route from origin → destination using the waypoint catalog
        String initialRoute = shipmentAggregator.buildInitialRoute(
                shipment.getOriginWarehouse(), shipment.getDestinationStore());

        String products = shipment.getProducts() != null
                ? String.join(",", shipment.getProducts())
                : "";

        ShipmentRecord record = new ShipmentRecord(
                shipment.getShipmentId(),
                shipment.getOriginWarehouse(),
                shipment.getDestinationStore(),
                shipment.getDriverId(),
                products,
                shipment.getTotalQuantity(),
                initialRoute
        );

        shipmentRepository.save(record);
        log.info("Persisted shipment {} with route: {}", shipment.getShipmentId(), initialRoute);
    }
}
