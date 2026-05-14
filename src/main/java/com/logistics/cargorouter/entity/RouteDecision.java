package com.logistics.cargorouter.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Immutable audit record for every routing decision made by the AgenticLoop.
 *
 * Mirrors JPMC's TransactionRecord — every agent action that changes state is
 * persisted here so decisions can be replayed, explained, or audited.
 */
@Entity
@Table(name = "route_decision")
public class RouteDecision {

    @Id
    @GeneratedValue
    private long id;

    @Column(nullable = false)
    private String shipmentId;

    @Column(nullable = false)
    private String driverId;

    @Column(length = 1024)
    private String previousRoute;

    @Column(length = 1024)
    private String newRoute;

    /** weatherRisk score of the previous route (0.0–1.0). */
    private double previousRisk;

    /** weatherRisk score of the new route (0.0–1.0). */
    private double newRisk;

    /**
     * Human-readable trigger, derived from the worst WMO weather code,
     * e.g. "WEATHER_THUNDERSTORM", "WEATHER_HEAVY_SNOW".
     */
    @Column(nullable = false)
    private String trigger;

    @Column(nullable = false)
    private Instant decidedAt;

    protected RouteDecision() {}

    public RouteDecision(String shipmentId, String driverId,
                         String previousRoute, String newRoute,
                         double previousRisk, double newRisk, String trigger) {
        this.shipmentId = shipmentId;
        this.driverId = driverId;
        this.previousRoute = previousRoute;
        this.newRoute = newRoute;
        this.previousRisk = previousRisk;
        this.newRisk = newRisk;
        this.trigger = trigger;
        this.decidedAt = Instant.now();
    }

    public long    getId()           { return id; }
    public String  getShipmentId()   { return shipmentId; }
    public String  getDriverId()     { return driverId; }
    public String  getPreviousRoute(){ return previousRoute; }
    public String  getNewRoute()     { return newRoute; }
    public double  getPreviousRisk() { return previousRisk; }
    public double  getNewRisk()      { return newRisk; }
    public String  getTrigger()      { return trigger; }
    public Instant getDecidedAt()    { return decidedAt; }

    @Override
    public String toString() {
        return "RouteDecision{shipment='" + shipmentId + "', trigger='" + trigger
                + "', riskDelta=" + (newRisk - previousRisk) + "}";
    }
}
