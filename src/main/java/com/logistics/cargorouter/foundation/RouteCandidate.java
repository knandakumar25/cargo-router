package com.logistics.cargorouter.foundation;

/**
 * A candidate route considered during re-routing evaluation.
 *
 * Implements Comparable so it can be used directly in a java.util.PriorityQueue
 * (min-heap by compositeScore). This is the core of the heap-optimised route
 * selection algorithm adapted from Walmart's shipping aggregation task:
 *
 *   Walmart:  defaultdict + linear scan over shipment groups → O(k²)
 *   Here:     PriorityQueue<RouteCandidate>                  → O(k log k)
 *
 * compositeScore = (weatherRisk * 0.70) + (normalizedDelay * 0.30)
 * Polling heap.poll() always returns the globally cheapest route in O(log k).
 */
public class RouteCandidate implements Comparable<RouteCandidate> {

    private final String   shipmentId;
    private final String[] waypoints;         // ordered stops: origin → … → destination
    private final double   weatherRisk;       // 0.0–1.0, worst waypoint score
    private final double   estimatedDelayHrs; // positive = slower than current route
    private final double   compositeScore;    // lower is better

    public RouteCandidate(String shipmentId, String[] waypoints,
                          double weatherRisk, double estimatedDelayHrs) {
        this.shipmentId = shipmentId;
        this.waypoints = waypoints;
        this.weatherRisk = weatherRisk;
        this.estimatedDelayHrs = estimatedDelayHrs;
        this.compositeScore = (weatherRisk * 0.70) + (normalise(estimatedDelayHrs) * 0.30);
    }

    /** Clamp delay hours to [0, 24] then normalise to [0, 1]. */
    private static double normalise(double delayHrs) {
        return Math.max(0.0, Math.min(delayHrs, 24.0)) / 24.0;
    }

    /** Min-heap: lower compositeScore surfaces first. */
    @Override
    public int compareTo(RouteCandidate other) {
        return Double.compare(this.compositeScore, other.compositeScore);
    }

    public String   getShipmentId()        { return shipmentId; }
    public String[] getWaypoints()         { return waypoints; }
    public double   getWeatherRisk()       { return weatherRisk; }
    public double   getEstimatedDelayHrs() { return estimatedDelayHrs; }
    public double   getCompositeScore()    { return compositeScore; }

    public String waypointsAsString() {
        return String.join(" → ", waypoints);
    }

    @Override
    public String toString() {
        return "RouteCandidate{shipment='" + shipmentId + "', route='" + waypointsAsString()
                + "', risk=" + weatherRisk + ", score=" + compositeScore + "}";
    }
}
