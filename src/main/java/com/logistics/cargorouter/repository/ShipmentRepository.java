package com.logistics.cargorouter.repository;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

import com.logistics.cargorouter.entity.ShipmentRecord;

public interface ShipmentRepository extends CrudRepository<ShipmentRecord, String> {

    /** Used by AgenticLoop each cycle to fetch all shipments under active monitoring. */
    List<ShipmentRecord> findByStatus(String status);

    ShipmentRecord findByShipmentId(String shipmentId);
}
