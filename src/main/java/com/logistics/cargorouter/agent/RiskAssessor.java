package com.logistics.cargorouter.agent;

import org.springframework.stereotype.Component;

import com.logistics.cargorouter.foundation.WeatherReport;

/**
 * Computes the aggregate weather risk for a full route.
 *
 * Strategy: the worst single waypoint dominates (max risk).
 * Rationale: a truck cannot teleport past a storm, so one severely
 * dangerous leg makes the entire route high-risk regardless of others.
 *
 * Returns a triggerLabel string alongside the score so the
 * AgenticLoop can record a human-readable reason in RouteDecision.
 */
@Component
public class RiskAssessor {

    private final WeatherMonitor weatherMonitor;

    public RiskAssessor(WeatherMonitor weatherMonitor) {
        this.weatherMonitor = weatherMonitor;
    }

    public AssessmentResult score(String[] waypoints) {
        double maxRisk     = 0.0;
        String worstCity   = waypoints.length > 0 ? waypoints[0] : "UNKNOWN";
        int    worstCode   = 0;
        double worstWind   = 0.0;

        for (String city : waypoints) {
            WeatherReport report = weatherMonitor.assess(city.trim());
            if (report.getRiskScore() > maxRisk) {
                maxRisk   = report.getRiskScore();
                worstCity = city;
                worstCode = report.getWeatherCode();
                worstWind = report.getWindSpeedKph();
            }
        }

        String trigger = buildTrigger(worstCode, worstWind, worstCity);
        return new AssessmentResult(maxRisk, trigger);
    }

    private String buildTrigger(int code, double wind, String city) {
        String condition;
        if      (code >= 95) condition = "THUNDERSTORM";
        else if (code >= 85) condition = "SNOW_SHOWERS";
        else if (code >= 80) condition = "RAIN_SHOWERS";
        else if (code >= 71) condition = "HEAVY_SNOW";
        else if (code >= 51) condition = "RAIN";
        else if (code >= 45) condition = "FOG";
        else if (wind > 80)  condition = "HIGH_WINDS";
        else                 condition = "CLEAR";
        return "WEATHER_" + condition + "@" + city.toUpperCase().replace(" ", "_");
    }

    public record AssessmentResult(double riskScore, String trigger) {}
}
