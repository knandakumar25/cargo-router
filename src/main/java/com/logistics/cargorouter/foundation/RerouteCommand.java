package com.logistics.cargorouter.foundation;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Outbound Kafka DTO published to the 'reroute-commands' topic.
 *
 * Downstream fleet systems (driver apps, dispatch dashboards) consume this
 * topic and update navigation in real time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RerouteCommand {

    private String  shipmentId;
    private String  driverId;
    private String  previousRoute;
    private String  recommendedRoute;
    private double  riskScore;
    private String  trigger;          // e.g. "WEATHER_STORM", "WEATHER_SNOW"
    private Instant issuedAt;

    public RerouteCommand() {}

    public RerouteCommand(String shipmentId, String driverId,
                          String previousRoute, String recommendedRoute,
                          double riskScore, String trigger) {
        this.shipmentId = shipmentId;
        this.driverId = driverId;
        this.previousRoute = previousRoute;
        this.recommendedRoute = recommendedRoute;
        this.riskScore = riskScore;
        this.trigger = trigger;
        this.issuedAt = Instant.now();
    }

    public String  getShipmentId()       { return shipmentId; }
    public String  getDriverId()         { return driverId; }
    public String  getPreviousRoute()    { return previousRoute; }
    public String  getRecommendedRoute() { return recommendedRoute; }
    public double  getRiskScore()        { return riskScore; }
    public String  getTrigger()          { return trigger; }
    public Instant getIssuedAt()         { return issuedAt; }

    public void setShipmentId(String shipmentId)             { this.shipmentId = shipmentId; }
    public void setDriverId(String driverId)                 { this.driverId = driverId; }
    public void setPreviousRoute(String previousRoute)       { this.previousRoute = previousRoute; }
    public void setRecommendedRoute(String recommendedRoute) { this.recommendedRoute = recommendedRoute; }
    public void setRiskScore(double riskScore)               { this.riskScore = riskScore; }
    public void setTrigger(String trigger)                   { this.trigger = trigger; }
    public void setIssuedAt(Instant issuedAt)                { this.issuedAt = issuedAt; }

    @Override
    public String toString() {
        return "RerouteCommand{shipmentId='" + shipmentId + "', driver='" + driverId
                + "', trigger='" + trigger + "', route='" + recommendedRoute + "'}";
    }
}
