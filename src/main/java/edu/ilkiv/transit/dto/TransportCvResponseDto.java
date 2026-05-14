package edu.ilkiv.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Обгортка відповіді POST /api/positions з transport.cv.ua
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransportCvResponseDto {
    private List<TransportCvVehicleDto> transports;
}