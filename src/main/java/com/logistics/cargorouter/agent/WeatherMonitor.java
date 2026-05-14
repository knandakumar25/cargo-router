package com.logistics.cargorouter.agent;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.logistics.cargorouter.foundation.WeatherReport;

/**
 * Fetches live weather conditions for a named waypoint city.
 *
 * Mirrors JPMC's IncentiveService: a single RestTemplate.postForObject/getForObject
 * call that queries an external HTTP endpoint and maps the response to a domain DTO.
 *
 * API: Open-Meteo (https://open-meteo.com) — free, no API key required.
 * Endpoint: GET /v1/forecast?latitude={lat}&longitude={lon}&current_weather=true
 *
 * WMO weather code → risk score mapping:
 *   0–3   (clear / mainly clear)      → 0.05
 *   45–48 (fog)                       → 0.30
 *   51–67 (drizzle / rain)            → 0.45
 *   71–77 (snow)                      → 0.70
 *   80–82 (showers)                   → 0.50
 *   85–86 (snow showers)              → 0.75
 *   95–99 (thunderstorm / hail)       → 0.90
 */
@Component
public class WeatherMonitor {

    private static final Logger log = LoggerFactory.getLogger(WeatherMonitor.class);

    /** Lat/lon catalog for the freight corridor cities in ShipmentAggregator. */
    private static final Map<String, double[]> CITY_COORDS = Map.ofEntries(
            Map.entry("Chicago",         new double[]{41.8781, -87.6298}),
            Map.entry("Indianapolis",    new double[]{39.7684, -86.1581}),
            Map.entry("Louisville",      new double[]{38.2527, -85.7585}),
            Map.entry("Nashville",       new double[]{36.1627, -86.7816}),
            Map.entry("Atlanta",         new double[]{33.7490, -84.3880}),
            Map.entry("Memphis",         new double[]{35.1495, -90.0490}),
            Map.entry("Dallas",          new double[]{32.7767, -96.7970}),
            Map.entry("Houston",         new double[]{29.7604, -95.3698}),
            Map.entry("Denver",          new double[]{39.7392, -104.9903}),
            Map.entry("Salt Lake City",  new double[]{40.7608, -111.8910}),
            Map.entry("Los Angeles",     new double[]{34.0522, -118.2437}),
            Map.entry("Seattle",         new double[]{47.6062, -122.3321}),
            Map.entry("New York",        new double[]{40.7128, -74.0060}),
            Map.entry("Philadelphia",    new double[]{39.9526, -75.1652}),
            Map.entry("Pittsburgh",      new double[]{40.4406, -79.9959}),
            Map.entry("Columbus",        new double[]{39.9612, -82.9988}),
            Map.entry("Cleveland",       new double[]{41.4993, -81.6944}),
            Map.entry("Detroit",         new double[]{42.3314, -83.0458}),
            Map.entry("Milwaukee",       new double[]{43.0389, -87.9065}),
            Map.entry("St. Louis",       new double[]{38.6270, -90.1994}),
            Map.entry("Kansas City",     new double[]{39.0997, -94.5786}),
            Map.entry("Cincinnati",      new double[]{39.1031, -84.5120}),
            Map.entry("Charlotte",       new double[]{35.2271, -80.8431}),
            Map.entry("Birmingham",      new double[]{33.5186, -86.8104}),
            Map.entry("Little Rock",     new double[]{34.7465, -92.2896}),
            Map.entry("Oklahoma City",   new double[]{35.4676, -97.5164}),
            Map.entry("Austin",          new double[]{30.2672, -97.7431}),
            Map.entry("San Antonio",     new double[]{29.4241, -98.4936}),
            Map.entry("Albuquerque",     new double[]{35.0844, -106.6504}),
            Map.entry("Las Vegas",       new double[]{36.1699, -115.1398}),
            Map.entry("Phoenix",         new double[]{33.4484, -112.0740}),
            Map.entry("Portland",        new double[]{45.5051, -122.6750}),
            Map.entry("Boston",          new double[]{42.3601, -71.0589}),
            Map.entry("Baltimore",       new double[]{39.2904, -76.6122})
    );

    private final RestTemplate restTemplate;

    @Value("${general.weather-api-url}")
    private String weatherApiUrl;

    public WeatherMonitor(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Query live weather for a waypoint city and return a normalised WeatherReport.
     * On any API failure the method returns a zero-risk report (fail-open) so a
     * transient network error does not halt the entire agentic cycle.
     */
    public WeatherReport assess(String city) {
        double[] coords = CITY_COORDS.get(city);
        if (coords == null) {
            log.debug("No coordinates for city '{}' — returning zero-risk report", city);
            return new WeatherReport(city, 0.0, 0, 0.0, 0.0);
        }

        try {
            String url = weatherApiUrl
                    + "?latitude=" + coords[0]
                    + "&longitude=" + coords[1]
                    + "&current_weather=true";

            OpenMeteoResponse response = restTemplate.getForObject(url, OpenMeteoResponse.class);
            if (response == null || response.getCurrentWeather() == null) {
                log.warn("Null weather response for {} — returning zero-risk", city);
                return new WeatherReport(city, 0.0, 0, 0.0, 0.0);
            }

            int    code  = response.getCurrentWeather().getWeathercode();
            double wind  = response.getCurrentWeather().getWindspeed();
            double risk  = codeToRisk(code, wind);

            log.debug("Weather for {}: code={}, wind={}kph, risk={}", city, code, wind, risk);
            return new WeatherReport(city, wind, code, 0.0, risk);

        } catch (RestClientException e) {
            log.warn("Weather API unavailable for {} ({}), defaulting to zero risk", city, e.getMessage());
            return new WeatherReport(city, 0.0, 0, 0.0, 0.0);
        }
    }

    /** Map WMO weather code + wind speed to a [0,1] risk score. */
    double codeToRisk(int code, double windKph) {
        double baseRisk;
        if      (code >= 95)              baseRisk = 0.90;  // thunderstorm / hail
        else if (code >= 85)              baseRisk = 0.75;  // snow showers
        else if (code >= 80)              baseRisk = 0.50;  // rain showers
        else if (code >= 71)              baseRisk = 0.70;  // snow
        else if (code >= 51)              baseRisk = 0.45;  // drizzle / rain
        else if (code >= 45)              baseRisk = 0.30;  // fog
        else                              baseRisk = 0.05;  // clear / cloudy

        // High winds (> 80 kph) add up to 0.15 extra risk
        double windPenalty = Math.min((windKph / 80.0) * 0.15, 0.15);
        return Math.min(baseRisk + windPenalty, 1.0);
    }

    // ─── inner response classes (Open-Meteo JSON shape) ─────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenMeteoResponse {
        private CurrentWeather current_weather;
        public CurrentWeather getCurrentWeather() { return current_weather; }
        public void setCurrentWeather(CurrentWeather cw) { this.current_weather = cw; }
        // Jackson needs snake_case field name directly
        public CurrentWeather getCurrent_weather() { return current_weather; }
        public void setCurrent_weather(CurrentWeather cw) { this.current_weather = cw; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrentWeather {
        private int    weathercode;
        private double windspeed;
        public int    getWeathercode() { return weathercode; }
        public double getWindspeed()   { return windspeed; }
        public void setWeathercode(int wc)   { this.weathercode = wc; }
        public void setWindspeed(double ws)  { this.windspeed = ws; }
    }
}
