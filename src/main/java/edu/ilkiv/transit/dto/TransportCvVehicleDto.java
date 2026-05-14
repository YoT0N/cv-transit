package edu.ilkiv.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DTO для одного транспортного засобу з transport.cv.ua
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransportCvVehicleDto {

    private Long id;
    private String transportNumber; // бортовий номер "5453"
    private Double lat;
    private Double lon;             // увага: lon, не lng!
    private Integer angle;          // напрямок
    private String datetime;        // "2026-05-13T19:48:50Z"
    private Integer speed;
    private String statusName;      // "stay", "move"
    private Long rtsId;             // id маршруту в цій системі
}