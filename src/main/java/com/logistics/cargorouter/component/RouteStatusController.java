package com.logistics.cargorouter.component;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.logistics.cargorouter.entity.RouteDecision;
import com.logistics.cargorouter.entity.ShipmentRecord;
import com.logistics.cargorouter.repository.RouteDecisionRepository;
import com.logistics.cargorouter.repository.ShipmentRepository;

/**
 * REST API for querying shipment state and decision history.
 *
 * Mirrors JPMC's BalanceController pattern: thin controller, all state
 * lives in JPA repositories, returns null-safe responses.
 *
 * Endpoints:
 *   GET /shipment/{id}/route      → current route + status
 *   GET /shipment/{id}/decisions  → full reroute audit log, newest first
 *   GET /shipments/active         → all shipments under monitoring
 */
@RestController
@RequestMapping("/shipment")
public class RouteStatusController {

    private final ShipmentRepository      shipmentRepository;
    private final RouteDecisionRepository decisionRepository;

    public RouteStatusController(ShipmentRepository shipmentRepository,
                                  RouteDecisionRepository decisionRepository) {
        this.shipmentRepository = shipmentRepository;
        this.decisionRepository = decisionRepository;
    }

    @GetMapping("/{id}/route")
    public ResponseEntity<ShipmentRecord> getRoute(@PathVariable String id) {
        ShipmentRecord record = shipmentRepository.findByShipmentId(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    @GetMapping("/{id}/decisions")
    public ResponseEntity<List<RouteDecision>> getDecisions(@PathVariable String id) {
        List<RouteDecision> decisions =
                decisionRepository.findByShipmentIdOrderByDecidedAtDesc(id);
        return ResponseEntity.ok(decisions);
    }

    @GetMapping("/active")
    public List<ShipmentRecord> getActiveShipments() {
        List<ShipmentRecord> active = shipmentRepository.findByStatus("ACTIVE");
        active.addAll(shipmentRepository.findByStatus("REROUTED"));
        return active;
    }
}
