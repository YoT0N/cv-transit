package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.*;
import edu.ilkiv.transit.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

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

    private static final Map<DataSource, Integer> SOURCE_PRIORITY = Map.of(
            DataSource.easyway,     1,
            DataSource.transgps,    2,
            DataSource.nimbus,      3,
            DataSource.transportcv, 4
    );

    @Transactional
    public void processPositions(List<VehiclePositionDto> positions) {
        if (positions.isEmpty()) return;

        // Всі позиції в одному батчі завжди від одного джерела
        DataSource source = positions.get(0).getSource();

        // ── Крок 1: дедублікація всередині батчу (по busNumber) ──────────────
        List<VehiclePositionDto> deduplicated = deduplicateWithinBatch(positions);
        log.debug("processPositions [{}]: {} after dedup (was {})",
                source, deduplicated.size(), positions.size());

        // ── Крок 2: зберігаємо оновлені засоби ───────────────────────────────
        List<Vehicle> updated = new ArrayList<>();
        Set<String> seenExternalIds = new HashSet<>();

        for (VehiclePositionDto dto : deduplicated) {
            try {
                Vehicle v = processOne(dto);
                if (v != null) {
                    updated.add(v);
                    seenExternalIds.add(dto.getExternalId());
                }
            } catch (Exception e) {
                log.error("Failed to process vehicle externalId={} source={}: {}",
                        dto.getExternalId(), source, e.getMessage(), e);
            }
        }

        // ── Крок 3: позначаємо офлайн тих кого НЕ було в цьому батчі ─────────
        // EasyWay і TransGPS надсилають ПОВНИЙ список активного транспорту.
        // Якщо засіб був онлайн але не прийшов — він більше не активний.
        markAbsentVehiclesOffline(source, seenExternalIds);

        if (!updated.isEmpty()) {
            broadcastService.broadcast(updated);
            vehicleService.evictCache();
        }
    }

    /**
     * Позначає як офлайн усі засоби від даного джерела,
     * яких не було у поточному батчі.
     *
     * Важливо: MQTT (Nimbus) надсилає по одному повідомленню на засіб —
     * там батч завжди містить 1 елемент, тому цю логіку для Nimbus
     * НЕ застосовуємо (інакше всі інші Nimbus-засоби стали б офлайн).
     */
    private void markAbsentVehiclesOffline(DataSource source, Set<String> seenExternalIds) {
        // Nimbus — push по одному, не знаємо повний список → пропускаємо
        if (source == DataSource.nimbus) return;

        List<Vehicle> nowOffline = vehicleRepository
                .findBySourceAndIsOnlineTrue(source)
                .stream()
                .filter(v -> !seenExternalIds.contains(v.getExternalId()))
                .toList();

        if (nowOffline.isEmpty()) return;

        nowOffline.forEach(v -> v.setIsOnline(false));
        vehicleRepository.saveAll(nowOffline);

        log.debug("markAbsentVehiclesOffline [{}]: {} vehicles set offline",
                source, nowOffline.size());
    }

    // ── Дедублікація в межах батчу ────────────────────────────────────────────

    private List<VehiclePositionDto> deduplicateWithinBatch(List<VehiclePositionDto> positions) {
        Map<String, VehiclePositionDto> seen = new LinkedHashMap<>();
        for (VehiclePositionDto dto : positions) {
            String key = buildDeduplicationKey(dto);
            seen.merge(key, dto, (existing, incoming) -> {
                log.debug("Intra-batch dedup [{}]: dropping externalId={} (dup of {}) key={}",
                        incoming.getSource(), incoming.getExternalId(),
                        existing.getExternalId(), key);
                return existing;
            });
        }
        return new ArrayList<>(seen.values());
    }

    private String buildDeduplicationKey(VehiclePositionDto dto) {
        if (dto.getBusNumber() != null && !dto.getBusNumber().isBlank()) {
            return dto.getSource().name() + ":" + dto.getBusNumber();
        }
        return dto.getSource().name() + ":id:" + dto.getExternalId();
    }

    // ── Збереження одного засобу ──────────────────────────────────────────────

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
        if (dto.getBusNumber() != null) vehicle.setBusNumber(dto.getBusNumber());

        vehicleRepository.save(vehicle);

        if (route != null) deduplicateNearby(vehicle, route, dto.getSource());

        gpsHistoryRepository.save(GpsHistory.builder()
                .vehicle(vehicle)
                .lat(dto.getLat())
                .lng(dto.getLng())
                .speed(dto.getSpeed())
                .source(dto.getSource())
                .build());

        return vehicle;
    }

    // ── Дедублікація між джерелами ────────────────────────────────────────────

    private void deduplicateNearby(Vehicle current, Route route, DataSource currentSource) {
        int currentPriority = SOURCE_PRIORITY.getOrDefault(currentSource, 99);

        List<Vehicle> nearby = vehicleRepository.findNearbyFromOtherSource(
                current.getLat(), current.getLng(), 200.0, currentSource, route);

        for (Vehicle other : nearby) {
            int otherPriority = SOURCE_PRIORITY.getOrDefault(other.getSource(), 99);
            if (currentPriority < otherPriority) {
                if (other.getIsOnline()) {
                    other.setIsOnline(false);
                    vehicleRepository.save(other);
                }
            } else {
                current.setIsOnline(false);
                vehicleRepository.save(current);
            }
        }
    }

    // ── Резолюція маршруту ────────────────────────────────────────────────────

    private Route resolveRoute(VehiclePositionDto dto) {
        if (dto.getExternalRouteId() == null && dto.getRouteName() == null) return null;

        TransportType type = dto.getType() != null ? dto.getType() : TransportType.BUS;

        // ── Крок 1: шукаємо існуючий source_mapping ──────────────────────────
        if (dto.getExternalRouteId() != null) {
            Optional<SourceMapping> mapping = sourceMappingRepository
                    .findByEntityTypeAndSourceAndSourceId(
                            "route", dto.getSource().name(), dto.getExternalRouteId());
            if (mapping.isPresent()) {
                return routeRepository.findById(mapping.get().getCanonicalId()).orElse(null);
            }
        }

        // ── Крок 2: шукаємо по (name, type) — обидва поля обов'язкові ────────
        // НЕ робимо fallback тільки по name — це змішує маршрути з однаковою
        // назвою але різним типом (наприклад автобус "3" і тролейбус "3").
        Route route = null;
        if (dto.getRouteName() != null) {
            route = routeRepository.findByNameAndType(dto.getRouteName(), type).orElse(null);

            log.debug("resolveRoute: lookup by name='{}' type='{}' → {}",
                    dto.getRouteName(), type,
                    route != null ? "found id=" + route.getId() : "not found");
        }

        // ── Крок 3: створюємо якщо не знайшли ────────────────────────────────
        if (route == null) {
            String name = dto.getRouteName() != null
                    ? dto.getRouteName()
                    : "id:" + dto.getExternalRouteId();

            route = routeRepository.save(Route.builder()
                    .name(name)
                    .type(type)
                    .color(dto.getColor())
                    .isActive(true)
                    .build());

            log.info("resolveRoute: created route '{}' type={} (source={}, externalRouteId={})",
                    route.getName(), type, dto.getSource(), dto.getExternalRouteId());
        }

        // ── Крок 4: зберігаємо маппінг для наступних запитів ─────────────────
        if (dto.getExternalRouteId() != null) {
            try {
                sourceMappingRepository.save(SourceMapping.builder()
                        .entityType("route")
                        .canonicalId(route.getId())
                        .source(dto.getSource())
                        .sourceId(dto.getExternalRouteId())
                        .build());
            } catch (Exception e) {
                log.debug("resolveRoute: mapping already exists for source={} routeId={}",
                        dto.getSource(), dto.getExternalRouteId());
            }
        }

        return route;
    }
}