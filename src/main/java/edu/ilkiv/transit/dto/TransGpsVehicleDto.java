package edu.ilkiv.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * DTO для одного транспортного засобу з trans-gps.cv.ua
 * GET https://trans-gps.cv.ua/map/tracker/?selectedRoutesStr=
 * Відповідь — Map<String(imei), TransGpsVehicleDto>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransGpsVehicleDto {

    private Long id;
    private String imei;
    private String name;         // внутрішня назва ("A17")

    private Double lat;
    private Double lng;
    private String speed;        // рядок! "007.4"
    private String orientation;  // рядок! "029.06"
    private String gpstime;      // "2026-05-13 17:49:24"

    @JsonProperty("routeId")
    private Integer routeId;

    @JsonProperty("routeName")
    private String routeName;    // "10", "9A" — людська назва маршруту

    @JsonProperty("routeColour")
    private String routeColour;

    @JsonProperty("inDepo")
    private Boolean inDepo;

    @JsonProperty("busNumber")
    private String busNumber;    // бортовий номер "4811"

    @JsonProperty("perevName")
    private String perevName;    // ім'я перевізника

    private Boolean online;
}