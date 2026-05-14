package com.logistics.cargorouter.foundation;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound Kafka DTO — mirrors Walmart's shipment data model.
 *
 * Walmart data had three CSVs: per-product lines (data_1), per-shipment metadata (data_2),
 * and combined rows (data_0). Here every field lands in one message, which the
 * ShipmentAggregator reconciles when multi-leg updates arrive out-of-order.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CargoShipment {

    private String shipmentId;
    private String originWarehouse;
    private String destinationStore;
    private String driverId;
    private List<String> products;
    private int totalQuantity;

    public CargoShipment() {}

    public CargoShipment(String shipmentId, String originWarehouse,
                         String destinationStore, String driverId,
                         List<String> products, int totalQuantity) {
        this.shipmentId = shipmentId;
        this.originWarehouse = originWarehouse;
        this.destinationStore = destinationStore;
        this.driverId = driverId;
        this.products = products;
        this.totalQuantity = totalQuantity;
    }

    public String getShipmentId()       { return shipmentId; }
    public String getOriginWarehouse()  { return originWarehouse; }
    public String getDestinationStore() { return destinationStore; }
    public String getDriverId()         { return driverId; }
    public List<String> getProducts()   { return products; }
    public int getTotalQuantity()       { return totalQuantity; }

    public void setShipmentId(String shipmentId)             { this.shipmentId = shipmentId; }
    public void setOriginWarehouse(String originWarehouse)   { this.originWarehouse = originWarehouse; }
    public void setDestinationStore(String destinationStore) { this.destinationStore = destinationStore; }
    public void setDriverId(String driverId)                 { this.driverId = driverId; }
    public void setProducts(List<String> products)           { this.products = products; }
    public void setTotalQuantity(int totalQuantity)          { this.totalQuantity = totalQuantity; }

    @Override
    public String toString() {
        return "CargoShipment{shipmentId='" + shipmentId + "', origin='" + originWarehouse
                + "', destination='" + destinationStore + "', qty=" + totalQuantity + "}";
    }
}
