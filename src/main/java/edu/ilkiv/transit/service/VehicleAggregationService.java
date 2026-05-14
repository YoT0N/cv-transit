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
 * Отримує VehiclePositionDto з будь-якого колектора,
 * зводить до канонічних маршрутів і зберігає в БД.
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
                if (v != null) updated.add(v);
            } catch (Exception e) {
                log.error("Failed to process vehicle {} from {}: {}",
                        dto.getExternalId(), dto.getSource(), e.getMessage());
            }
        }

        // Push до всіх WebSocket клієнтів
        if (!updated.isEmpty()) {
            broadcastService.broadcast(updated);
            vehicleService.evictCache(); // інвалідуємо кеш після оновлення
        }
    }

    private Vehicle processOne(VehiclePositionDto dto) {
        // 1. Знайти або створити канонічний маршрут
        Route route = resolveRoute(dto);

        // 2. Знайти або створити vehicle
        Vehicle vehicle = vehicleRepository
                .findByExternalIdAndSource(dto.getExternalId(), dto.getSource())
                .orElseGet(() -> Vehicle.builder()
                        .externalId(dto.getExternalId())
                        .source(dto.getSource())
                        .build());

        // 3. Оновити поточну позицію
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

        // 4. Зберегти точку в GPS-історію (асинхронно через save)
        GpsHistory history = GpsHistory.builder()
                .vehicle(vehicle)
                .lat(dto.getLat())
                .lng(dto.getLng())
                .speed(dto.getSpeed())
                .source(dto.getSource())
                .build();
        gpsHistoryRepository.save(history);
        vehicleRepository.save(vehicle);
        gpsHistoryRepository.save(history);
        return vehicle;
    }

    /**
     * Знаходить канонічний Route для dto.
     * Алгоритм:
     *  1. Шукаємо в source_mappings чи вже знаємо цей (source, externalRouteId)
     *  2. Якщо є — повертаємо відповідний Route
     *  3. Якщо ні — шукаємо Route за назвою (routeName)
     *  4. Якщо і його нема — створюємо новий Route
     *  5. Зберігаємо маппінг
     */
    private Route resolveRoute(VehiclePositionDto dto) {
        if (dto.getExternalRouteId() == null && dto.getRouteName() == null) {
            return null;
        }

        // Крок 1: шукаємо існуючий маппінг
        if (dto.getExternalRouteId() != null) {
            Optional<SourceMapping> mapping = sourceMappingRepository
                    .findByEntityTypeAndSourceAndSourceId(
                            "route",
                            dto.getSource().name(),   // ← .name() бо нативний запит приймає String
                            dto.getExternalRouteId());

            if (mapping.isPresent()) {
                return routeRepository.findById(mapping.get().getCanonicalId())
                        .orElse(null);
            }
        }

        // Крок 2: шукаємо за назвою маршруту
        Route route = null;
        if (dto.getRouteName() != null) {
            route = routeRepository.findByName(dto.getRouteName()).orElse(null);
        }

        // Крок 3: створюємо новий маршрут
        if (route == null) {
            route = routeRepository.save(Route.builder()
                    .name(dto.getRouteName() != null ? dto.getRouteName() : "?")
                    .type(dto.getType() != null ? dto.getType() : TransportType.BUS)
                    .color(dto.getColor())
                    .isActive(true)
                    .build());
            log.info("Created new route: '{}'", route.getName());
        }

        // Крок 4: зберігаємо маппінг щоб наступного разу не шукати
        if (dto.getExternalRouteId() != null) {
            sourceMappingRepository.save(SourceMapping.builder()
                    .entityType("route")
                    .canonicalId(route.getId())
                    .source(dto.getSource())
                    .sourceId(dto.getExternalRouteId())
                    .build());
        }

        return route;
    }
}