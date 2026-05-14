package com.logistics.cargorouter.repository;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

import com.logistics.cargorouter.entity.RouteDecision;

public interface RouteDecisionRepository extends CrudRepository<RouteDecision, Long> {

    /** Returns the full decision history for a shipment, newest-first for the REST API. */
    List<RouteDecision> findByShipmentIdOrderByDecidedAtDesc(String shipmentId);
}
