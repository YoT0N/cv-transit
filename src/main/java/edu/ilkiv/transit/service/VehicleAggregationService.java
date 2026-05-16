package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.*;
import edu.ilkiv.transit.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Центральний сервіс агрегації.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleAggregationService {

    private final VehicleRepository vehicleRepository;
    private final RouteRepository routeRepository;
    private final SourceMappingRepository sourceMappingRepository;
    private final GpsHistoryRepository gpsHistoryRepository;
    private final VehicleBroadcastService broadcastService;
    private final VehicleService vehicleService;

    @Transactional
    public void processPositions(List<VehiclePositionDto> positions) {
        List<Vehicle> updated = new ArrayList<>();

        for (VehiclePositionDto dto : positions) {
            try {
                Vehicle v = processOne(dto);
                if (v != null) {
                    updated.add(v);
                    log.debug("Processed vehicle externalId={} source={} route='{}'",
                            dto.getExternalId(), dto.getSource(),
                            v.getRoute() != null ? v.getRoute().getName() : "null");
                }
            } catch (Exception e) {
                log.error("Failed to process vehicle externalId={} source={} routeName='{}': {}",
                        dto.getExternalId(), dto.getSource(), dto.getRouteName(), e.getMessage(), e);
            }
        }

        log.debug("processPositions: {}/{} vehicles saved successfully",
                updated.size(), positions.size());

        if (!updated.isEmpty()) {
            broadcastService.broadcast(updated);
            vehicleService.evictCache();
        }
    }

    private Vehicle processOne(VehiclePositionDto dto) {
        Route route = resolveRoute(dto);

        Vehicle vehicle = vehicleRepository
                .findByExternalIdAndSource(dto.getExternalId(), dto.getSource())
                .orElseGet(() -> Vehicle.builder()
                        .externalId(dto.getExternalId())
                        .source(dto.getSource())
                        .build());

        vehicle.setRoute(route);
        vehicle.setLat(dto.getLat());
        vehicle.setLng(dto.getLng());
        vehicle.setSpeed(dto.getSpeed());
        vehicle.setBearing(dto.getBearing());
        vehicle.setIsOnline(Boolean.TRUE.equals(dto.getOnline()));
        vehicle.setLastSeen(OffsetDateTime.now());
        if (dto.getBusNumber() != null) {
            vehicle.setBusNumber(dto.getBusNumber());
        }
        vehicleRepository.save(vehicle);

        GpsHistory history = GpsHistory.builder()
                .vehicle(vehicle)
                .lat(dto.getLat())
                .lng(dto.getLng())
                .speed(dto.getSpeed())
                .source(dto.getSource())
                .build();
        gpsHistoryRepository.save(history);
        return vehicle;
    }

    private Route resolveRoute(VehiclePositionDto dto) {
        if (dto.getExternalRouteId() == null && dto.getRouteName() == null) {
            log.debug("resolveRoute: no routeId/routeName for externalId={}, returning null",
                    dto.getExternalId());
            return null;
        }

        // Крок 1: шукаємо існуючий маппінг
        if (dto.getExternalRouteId() != null) {
            Optional<SourceMapping> mapping = sourceMappingRepository
                    .findByEntityTypeAndSourceAndSourceId(
                            "route",
                            dto.getSource().name(),
                            dto.getExternalRouteId());

            if (mapping.isPresent()) {
                Route found = routeRepository.findById(mapping.get().getCanonicalId()).orElse(null);
                log.debug("resolveRoute: found via mapping externalRouteId={} → route='{}'",
                        dto.getExternalRouteId(), found != null ? found.getName() : "null");
                return found;
            }
        }

        // Крок 2: шукаємо за назвою маршруту
        Route route = null;
        if (dto.getRouteName() != null) {
            route = routeRepository.findByName(dto.getRouteName()).orElse(null);
            log.debug("resolveRoute: lookup by name='{}' → {}",
                    dto.getRouteName(), route != null ? "found id=" + route.getId() : "not found");
        }

        // Крок 3: створюємо новий маршрут
        if (route == null) {
            String name = dto.getRouteName() != null
                    ? dto.getRouteName()
                    : "id:" + dto.getExternalRouteId();

            route = routeRepository.save(Route.builder()
                    .name(name)
                    .type(dto.getType() != null ? dto.getType() : TransportType.BUS)
                    .color(dto.getColor())
                    .isActive(true)
                    .build());
            log.info("resolveRoute: created new route '{}' (source={}, externalRouteId={})",
                    route.getName(), dto.getSource(), dto.getExternalRouteId());
        }

        // Крок 4: зберігаємо маппінг
        if (dto.getExternalRouteId() != null) {
            // Перевіряємо чи маппінг вже існує (щоб уникнути UniqueConstraint violation)
            boolean exists = sourceMappingRepository
                    .findByEntityTypeAndSourceAndSourceId(
                            "route", dto.getSource().name(), dto.getExternalRouteId())
                    .isPresent();
            if (!exists) {
                sourceMappingRepository.save(SourceMapping.builder()
                        .entityType("route")
                        .canonicalId(route.getId())
                        .source(dto.getSource())
                        .sourceId(dto.getExternalRouteId())
                        .build());
            }
        }

        return route;
    }
}