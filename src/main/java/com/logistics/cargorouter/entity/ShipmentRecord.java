package com.logistics.cargorouter.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persisted shipment — mirrors Walmart's shipment table schema.
 *
 * Walmart normalized: product(id, name) + shipment(product_id, qty, origin, destination).
 * Here: single denormalized record per shipment for query simplicity; products
 * are stored as a comma-separated string (suitable for H2 demo; use @ElementCollection
 * with a proper DB in production).
 */
@Entity
@Table(name = "shipment_record")
public class ShipmentRecord {

    @Id
    @Column(nullable = false)
    private String shipmentId;

    @Column(nullable = false)
    private String originWarehouse;

    @Column(nullable = false)
    private String destinationStore;

    @Column(nullable = false)
    private String driverId;

    /** Comma-separated product names, e.g. "lotion,skis,bikes" */
    @Column(length = 1024)
    private String products;

    private int totalQuantity;

    /**
     * Comma-separated waypoints representing the active route,
     * e.g. "Chicago,Indianapolis,Louisville,Nashville".
     * Updated in-place when AgenticLoop issues a reroute.
     */
    @Column(length = 1024)
    private String currentRoute;

    /**
     * ACTIVE   → in transit, being monitored
     * REROUTED → at least one reroute issued this trip
     * DELIVERED → journey complete (no longer monitored)
     */
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastUpdatedAt;

    protected ShipmentRecord() {}

    public ShipmentRecord(String shipmentId, String originWarehouse, String destinationStore,
                          String driverId, String products, int totalQuantity, String currentRoute) {
        this.shipmentId = shipmentId;
        this.originWarehouse = originWarehouse;
        this.destinationStore = destinationStore;
        this.driverId = driverId;
        this.products = products;
        this.totalQuantity = totalQuantity;
        this.currentRoute = currentRoute;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.lastUpdatedAt = this.createdAt;
    }

    public String  getShipmentId()       { return shipmentId; }
    public String  getOriginWarehouse()  { return originWarehouse; }
    public String  getDestinationStore() { return destinationStore; }
    public String  getDriverId()         { return driverId; }
    public String  getProducts()         { return products; }
    public int     getTotalQuantity()    { return totalQuantity; }
    public String  getCurrentRoute()     { return currentRoute; }
    public String  getStatus()           { return status; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getLastUpdatedAt()    { return lastUpdatedAt; }

    public void setCurrentRoute(String currentRoute) {
        this.currentRoute = currentRoute;
        this.lastUpdatedAt = Instant.now();
    }

    public void setStatus(String status) {
        this.status = status;
        this.lastUpdatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "ShipmentRecord{id='" + shipmentId + "', status='" + status
                + "', route='" + currentRoute + "'}";
    }
}
