package com.logistics.cargorouter.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.logistics.cargorouter.entity.ShipmentRecord;
import com.logistics.cargorouter.foundation.CargoShipment;
import com.logistics.cargorouter.repository.ShipmentRepository;

/** Kafka consumer: validates inbound cargo events, builds the initial route, and persists the shipment. */
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

    /** Drops duplicates and messages with missing ID, origin, or destination. */
    @KafkaListener(topics = "${general.kafka-inbound-topic}")
    public void listen(CargoShipment shipment) {
        log.info("Received shipment event: {}", shipment);

        if (shipment.getShipmentId() == null || shipment.getShipmentId().isBlank()) {
            log.warn("Dropping shipment with null/empty ID");
            return;
        }
        String shipmentId = shipment.getShipmentId(); // non-null beyond this point

        if (shipment.getOriginWarehouse() == null || shipment.getDestinationStore() == null) {
            log.warn("Dropping shipment {} — missing origin or destination", shipmentId);
            return;
        }

        // Idempotency: skip if already persisted
        if (shipmentRepository.existsById(shipmentId)) {
            log.debug("Shipment {} already exists — skipping duplicate event", shipmentId);
            return;
        }

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
