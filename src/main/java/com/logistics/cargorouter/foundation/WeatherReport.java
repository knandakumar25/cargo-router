package com.logistics.cargorouter.foundation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Normalized weather response for a single geographic waypoint.
 *
 * Sourced from Open-Meteo's /v1/forecast endpoint (free, no key required).
 * WMO weather codes are mapped to a riskScore in WeatherMonitor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeatherReport {

    private String location;
    private double windSpeedKph;
    private int    weatherCode;   // WMO code: 0=clear, 95-99=thunderstorm
    private double precipitationProbability;

    /**
     * Normalised risk score in [0.0, 1.0]:
     *   0.00–0.10 → clear/mainly clear
     *   0.25–0.35 → fog / overcast
     *   0.40–0.50 → rain / drizzle
     *   0.65–0.75 → snow / heavy snow
     *   0.85–0.95 → thunderstorm / hail
     *
     * Computed by WeatherMonitor; stored here for downstream use.
     */
    private double riskScore;

    public WeatherReport() {}

    public WeatherReport(String location, double windSpeedKph, int weatherCode,
                         double precipitationProbability, double riskScore) {
        this.location = location;
        this.windSpeedKph = windSpeedKph;
        this.weatherCode = weatherCode;
        this.precipitationProbability = precipitationProbability;
        this.riskScore = riskScore;
    }

    public String getLocation()                   { return location; }
    public double getWindSpeedKph()               { return windSpeedKph; }
    public int    getWeatherCode()                { return weatherCode; }
    public double getPrecipitationProbability()   { return precipitationProbability; }
    public double getRiskScore()                  { return riskScore; }

    public void setLocation(String location)                               { this.location = location; }
    public void setWindSpeedKph(double windSpeedKph)                       { this.windSpeedKph = windSpeedKph; }
    public void setWeatherCode(int weatherCode)                            { this.weatherCode = weatherCode; }
    public void setPrecipitationProbability(double precipitationProbability) { this.precipitationProbability = precipitationProbability; }
    public void setRiskScore(double riskScore)                             { this.riskScore = riskScore; }

    @Override
    public String toString() {
        return "WeatherReport{location='" + location + "', code=" + weatherCode
                + ", windKph=" + windSpeedKph + ", risk=" + riskScore + "}";
    }
}
