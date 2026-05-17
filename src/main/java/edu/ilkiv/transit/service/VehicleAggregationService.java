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
import java.util.Map;
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

    // В VehicleAggregationService додай:
    private static final Map<DataSource, Integer> SOURCE_PRIORITY = Map.of(
            DataSource.easyway,     1,  // найвищий пріоритет
            DataSource.transgps,    2,
            DataSource.nimbus,      3,
            DataSource.transportcv, 4
    );

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

        // Дедублікація: якщо поруч є той самий маршрут з іншого джерела —
        // ховаємо те що має нижчий пріоритет
        if (route != null) {
            deduplicateNearby(vehicle, route, dto.getSource());
        }

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

    private void deduplicateNearby(Vehicle current, Route route, DataSource currentSource) {
        int currentPriority = SOURCE_PRIORITY.getOrDefault(currentSource, 99);

        List<Vehicle> nearby = vehicleRepository.findNearbyFromOtherSource(
                current.getLat(), current.getLng(), 200.0, currentSource, route);

        for (Vehicle other : nearby) {
            int otherPriority = SOURCE_PRIORITY.getOrDefault(other.getSource(), 99);

            if (currentPriority < otherPriority) {
                // Поточне джерело краще — ховаємо дублікат
                if (other.getIsOnline()) {
                    other.setIsOnline(false);
                    vehicleRepository.save(other);
                    log.debug("Dedup: hiding vehicle {} ({}) — duplicate of {} ({}) within 100m on route {}",
                            other.getId(), other.getSource(),
                            current.getId(), currentSource,
                            route.getName());
                }
            } else {
                // Інше джерело краще — ховаємо поточне
                current.setIsOnline(false);
                vehicleRepository.save(current);
                log.debug("Dedup: hiding vehicle {} ({}) — duplicate of {} ({}) within 100m on route {}",
                        current.getId(), currentSource,
                        other.getId(), other.getSource(),
                        route.getName());
            }
        }
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
            TransportType type = dto.getType() != null ? dto.getType() : TransportType.BUS;
            route = routeRepository.findByNameAndType(dto.getRouteName(), type).orElse(null);
            if (route == null) {
                route = routeRepository.findByName(dto.getRouteName())
                        .filter(r -> r.getType() == type || dto.getType() == null)
                        .orElse(null);
            }
            log.debug("resolveRoute: lookup by name='{}' type='{}' → {}",
                    dto.getRouteName(), type, route != null ? "found id=" + route.getId() : "not found");
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
            try {
                sourceMappingRepository.save(SourceMapping.builder()
                        .entityType("route")
                        .canonicalId(route.getId())
                        .source(dto.getSource())
                        .sourceId(dto.getExternalRouteId())
                        .build());
            } catch (Exception e) {
                // вже існує — ігноруємо duplicate key
                log.debug("resolveRoute: mapping already exists for source={} routeId={}",
                        dto.getSource(), dto.getExternalRouteId());
            }
        }

        return route;
    }
}