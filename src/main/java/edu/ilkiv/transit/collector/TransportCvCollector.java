package edu.ilkiv.transit.collector;

import edu.ilkiv.transit.dto.TransportCvResponseDto;
import edu.ilkiv.transit.dto.TransportCvVehicleDto;
import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.TransportType;
import edu.ilkiv.transit.service.VehicleAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

/**
 * Збирає позиції транспорту з transport.cv.ua кожні 30 секунд.
 * POST https://transport.cv.ua/api/positions
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "transportcv.collector.enabled", havingValue = "true", matchIfMissing = false)
public class TransportCvCollector {

    private static final String URL = "https://transport.cv.ua/api/positions";

    private final WebClient webClient;
    private final VehicleAggregationService aggregationService;

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void collect() {
        log.debug("TransportCvCollector: fetching...");
        try {
            TransportCvResponseDto response = webClient.post()
                    .uri(URL)
                    .contentType(new MediaType(MediaType.APPLICATION_FORM_URLENCODED, java.nio.charset.StandardCharsets.UTF_8))
                    .bodyValue("selectedRoutesStr=")  // порожній = всі маршрути
                    .retrieve()
                    .bodyToMono(TransportCvResponseDto.class)
                    .block();

            if (response == null || response.getTransports() == null) {
                log.warn("TransportCvCollector: empty response");
                return;
            }

            List<VehiclePositionDto> positions = response.getTransports().stream()
                    .filter(v -> v.getLat() != null && v.getLon() != null)
                    .map(this::toPositionDto)
                    .toList();

            log.debug("TransportCvCollector: got {} vehicles", positions.size());
            aggregationService.processPositions(positions);

        } catch (Exception e) {
            log.error("TransportCvCollector: failed to fetch data", e);
        }
    }

    private VehiclePositionDto toPositionDto(TransportCvVehicleDto dto) {
        return VehiclePositionDto.builder()
                .externalId(String.valueOf(dto.getId()))
                .source(DataSource.transportcv)
                .externalRouteId(String.valueOf(dto.getRtsId()))
                .type(TransportType.BUS)
                .lat(dto.getLat())
                .lng(dto.getLon())
                .speed(dto.getSpeed() != null ? dto.getSpeed().floatValue() : 0f)
                .bearing(dto.getAngle() != null ? dto.getAngle().floatValue() : 0f)
                .busNumber(dto.getTransportNumber())
                .online(!"stay".equals(dto.getStatusName()))
                .build();
    }
}