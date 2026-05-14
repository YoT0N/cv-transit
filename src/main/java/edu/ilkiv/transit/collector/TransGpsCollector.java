package edu.ilkiv.transit.collector;

import edu.ilkiv.transit.dto.TransGpsVehicleDto;
import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.TransportType;
import edu.ilkiv.transit.service.VehicleAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Збирає позиції транспорту з trans-gps.cv.ua кожні 30 секунд.
 * GET https://trans-gps.cv.ua/map/tracker/?selectedRoutesStr=
 * Відповідь: Map<String(imei), TransGpsVehicleDto>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransGpsCollector {

    private static final String URL =
            "https://trans-gps.cv.ua/map/tracker/?selectedRoutesStr=";

    private final WebClient webClient;
    private final VehicleAggregationService aggregationService;

    @Scheduled(fixedDelay = 30_000)
    public void collect() {
        log.debug("TransGpsCollector: fetching...");
        try {
            Map<String, TransGpsVehicleDto> response = webClient.get()
                    .uri(URL)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, TransGpsVehicleDto>>() {})
                    .block();

            if (response == null || response.isEmpty()) {
                log.warn("TransGpsCollector: empty response");
                return;
            }

            List<VehiclePositionDto> positions = response.values().stream()
                    .filter(v -> v.getLat() != null && v.getLng() != null)
                    .filter(v -> Boolean.TRUE.equals(v.getOnline()) || !Boolean.TRUE.equals(v.getInDepo()))
                    .map(this::toPositionDto)
                    .toList();

            log.debug("TransGpsCollector: got {} vehicles", positions.size());
            aggregationService.processPositions(positions);

        } catch (Exception e) {
            log.error("TransGpsCollector: failed to fetch data", e);
        }
    }

    private VehiclePositionDto toPositionDto(TransGpsVehicleDto dto) {
        float speed = 0f;
        try {
            speed = Float.parseFloat(dto.getSpeed().trim());
        } catch (Exception ignored) {}

        float bearing = 0f;
        try {
            bearing = Float.parseFloat(dto.getOrientation().trim());
        } catch (Exception ignored) {}

        return VehiclePositionDto.builder()
                .externalId(dto.getImei())
                .source(DataSource.transgps)
                .routeName(dto.getRouteName())
                .externalRouteId(String.valueOf(dto.getRouteId()))
                .type(TransportType.BUS)
                .lat(dto.getLat())
                .lng(dto.getLng())
                .speed(speed)
                .bearing(bearing)
                .busNumber(dto.getBusNumber())
                .color(dto.getRouteColour())
                .online(dto.getOnline())
                .build();
    }
}