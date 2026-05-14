package com.logistics.cargorouter.component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Aggregates route segment data into full multi-hop routes.
 *
 * ─── Walmart pattern mapping ────────────────────────────────────────────────
 * Walmart task 4 grouped product lines by shipment_identifier using a nested
 * defaultdict, then joined the result with a separate origins dict:
 *
 *   shipmentProducts = defaultdict(lambda: defaultdict(int))
 *   shipmentProducts[shipment_id][product] += 1          # group by ID
 *   origin, dest = origins[shipment_id]                  # join metadata
 *
 * Here the same two-phase pattern is used to aggregate route segments:
 *
 *   Phase 1 — group:  segmentIndex[origin] → List<String> downstream hops
 *   Phase 2 — join:   walk graph from origin to destination, expanding hops
 *
 * Result: a comma-separated waypoint string ready to store in ShipmentRecord.
 * ────────────────────────────────────────────────────────────────────────────
 *
 * The WAYPOINT_CATALOG maps city codes used in warehouse/store IDs to the
 * canonical city names recognised by WeatherMonitor. In production this would
 * be backed by a geographic service or a config server.
 */
@Component
public class ShipmentAggregator {

    /**
     * Phase 1 — segment index: origin city → list of next-hop cities.
     * Pre-loaded with the US freight corridor graph.
     * Mirrors Walmart's `shipmentProducts = defaultdict(lambda: defaultdict(int))`.
     */
    private static final Map<String, List<String>> SEGMENT_INDEX = new HashMap<>();

    /**
     * Phase 2 — metadata join: warehouse/store ID prefix → canonical city name.
     * Mirrors Walmart's `origins = {}` dict.
     */
    private static final Map<String, String> WAYPOINT_CATALOG = new HashMap<>();

    static {
        // Segment graph (directional edges of common freight corridors)
        putSegment("Chicago",       List.of("Indianapolis", "Milwaukee", "St. Louis"));
        putSegment("Indianapolis",  List.of("Louisville", "Columbus", "Cincinnati"));
        putSegment("Louisville",    List.of("Nashville", "Lexington", "Cincinnati"));
        putSegment("Nashville",     List.of("Atlanta", "Memphis", "Knoxville"));
        putSegment("Atlanta",       List.of("Charlotte", "Birmingham", "Savannah"));
        putSegment("Memphis",       List.of("Little Rock", "Jackson", "Nashville"));
        putSegment("Dallas",        List.of("Oklahoma City", "Austin", "Houston"));
        putSegment("Houston",       List.of("San Antonio", "Baton Rouge", "Austin"));
        putSegment("Denver",        List.of("Albuquerque", "Colorado Springs", "Salt Lake City"));
        putSegment("Salt Lake City",List.of("Las Vegas", "Boise", "Denver"));
        putSegment("Los Angeles",   List.of("San Diego", "Las Vegas", "Phoenix"));
        putSegment("Seattle",       List.of("Portland", "Boise", "Spokane"));
        putSegment("New York",      List.of("Philadelphia", "Boston", "Newark"));
        putSegment("Philadelphia",  List.of("Baltimore", "New York", "Pittsburgh"));
        putSegment("Pittsburgh",    List.of("Cleveland", "Columbus", "Philadelphia"));
        putSegment("Columbus",      List.of("Indianapolis", "Cleveland", "Pittsburgh"));
        putSegment("Cleveland",     List.of("Pittsburgh", "Detroit", "Columbus"));
        putSegment("Detroit",       List.of("Cleveland", "Chicago", "Toledo"));
        putSegment("Milwaukee",     List.of("Chicago", "Madison", "Green Bay"));
        putSegment("St. Louis",     List.of("Kansas City", "Memphis", "Chicago"));
        putSegment("Kansas City",   List.of("St. Louis", "Omaha", "Wichita"));

        // Warehouse/store location prefixes → city (mirrors Walmart's origins join)
        WAYPOINT_CATALOG.put("d5566b", "Chicago");
        WAYPOINT_CATALOG.put("c42f0d", "Dallas");
        WAYPOINT_CATALOG.put("b145f3", "Atlanta");
        WAYPOINT_CATALOG.put("f43722", "Los Angeles");
        WAYPOINT_CATALOG.put("50d337", "Nashville");
        WAYPOINT_CATALOG.put("172eb8", "Houston");
        WAYPOINT_CATALOG.put("65e454", "Denver");
        WAYPOINT_CATALOG.put("745bee", "Philadelphia");
        // Fallback is handled in resolveCity()
    }

    private static void putSegment(String from, List<String> to) {
        SEGMENT_INDEX.put(from, new ArrayList<>(to));
    }

    /**
     * Build the initial route for a new shipment as a comma-separated waypoint string.
     *
     * Phase 1 — resolve origin/destination IDs to city names (Walmart join step).
     * Phase 2 — walk segment graph from origin to destination (Walmart group step).
     */
    public String buildInitialRoute(String originId, String destinationId) {
        String origin      = resolveCity(originId);
        String destination = resolveCity(destinationId);

        List<String> path = findPath(origin, destination, new ArrayList<>(), 8);
        if (path.isEmpty()) {
            // Fallback: direct two-stop route
            return origin + "," + destination;
        }
        return String.join(",", path);
    }

    /**
     * Generate alternative routes for a given origin→destination pair.
     * Each alternative uses a different first hop from the segment graph.
     *
     * This produces the candidate list fed into RouteRanker's PriorityQueue.
     */
    public List<String[]> buildAlternatives(String currentRoute) {
        String[] waypoints = currentRoute.split(",");
        if (waypoints.length < 2) {
            return List.of();
        }
        String origin      = waypoints[0].trim();
        String destination = waypoints[waypoints.length - 1].trim();

        List<String> neighbors = SEGMENT_INDEX.getOrDefault(origin, List.of());
        List<String[]> alternatives = new ArrayList<>();

        for (String via : neighbors) {
            if (!via.equals(waypoints.length > 1 ? waypoints[1].trim() : "")) {
                List<String> altPath = new ArrayList<>();
                altPath.add(origin);
                altPath.add(via);
                List<String> remainder = findPath(via, destination, new ArrayList<>(List.of(origin, via)), 6);
                if (!remainder.isEmpty()) {
                    // remainder includes 'via' as first element; skip duplicate
                    altPath.addAll(remainder.subList(1, remainder.size()));
                } else {
                    altPath.add(destination);
                }
                alternatives.add(altPath.toArray(String[]::new));
            }
        }
        return alternatives;
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    /**
     * DFS path search through SEGMENT_INDEX with depth cap.
     * Returns the full path from 'current' to 'destination', or empty list if not found.
     */
    private List<String> findPath(String current, String destination,
                                   List<String> visited, int depthRemaining) {
        if (current.equals(destination)) {
            List<String> path = new ArrayList<>(visited);
            path.add(destination);
            return path;
        }
        if (depthRemaining == 0) {
            return List.of();
        }
        List<String> nextHops = SEGMENT_INDEX.getOrDefault(current, List.of());
        for (String next : nextHops) {
            if (!visited.contains(next)) {
                List<String> extended = new ArrayList<>(visited);
                extended.add(current);
                List<String> result = findPath(next, destination, extended, depthRemaining - 1);
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return List.of();
    }

    /**
     * Resolve a warehouse/store UUID prefix to a city name.
     * Mirrors Walmart's `origins[shipment_identifier]` lookup.
     */
    String resolveCity(String locationId) {
        if (locationId == null || locationId.length() < 6) {
            return "Chicago"; // safe default
        }
        String prefix = locationId.substring(0, 6).toLowerCase();
        return WAYPOINT_CATALOG.getOrDefault(prefix, toCityName(locationId));
    }

    /** Last-resort heuristic: strip UUID formatting, title-case first token. */
    private String toCityName(String id) {
        String[] parts = id.replace("-", " ").trim().split(" ");
        String first = parts[0];
        return first.substring(0, 1).toUpperCase() + first.substring(1).toLowerCase();
    }
}
