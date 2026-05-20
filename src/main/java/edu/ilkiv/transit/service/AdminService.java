package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.admin.*;
import edu.ilkiv.transit.model.*;
import edu.ilkiv.transit.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static edu.ilkiv.transit.config.CacheConfig.CACHE_ROUTES;
import static edu.ilkiv.transit.config.CacheConfig.CACHE_VEHICLES;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final VehicleRepository vehicleRepository;
    private final RouteRepository routeRepository;
    private final GpsHistoryRepository gpsHistoryRepository;
    private final SourceMappingRepository sourceMappingRepository;

    // ── GPS-історія ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<GpsHistoryResponseDto> getVehicleHistory(Long vehicleId, int hours) {
        OffsetDateTime since = OffsetDateTime.now().minusHours(hours);

        return gpsHistoryRepository
                .findByVehicleIdAndRecordedAtAfterOrderByRecordedAtAsc(vehicleId, since)
                .stream()
                .map(h -> GpsHistoryResponseDto.builder()
                        .id(h.getId())
                        .vehicleId(vehicleId)
                        .lat(h.getLat())
                        .lng(h.getLng())
                        .speed(h.getSpeed())
                        .source(h.getSource().name())
                        .recordedAt(h.getRecordedAt())
                        .build())
                .toList();
    }

    // ── Офлайн транспорт ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OfflineVehicleResponseDto> getOfflineVehicles() {
        return vehicleRepository.findAll().stream()
                .filter(v -> !Boolean.TRUE.equals(v.getIsOnline()))
                .map(v -> OfflineVehicleResponseDto.builder()
                        .id(v.getId())
                        .externalId(v.getExternalId())
                        .source(v.getSource().name())
                        .routeName(v.getRoute() != null ? v.getRoute().getName() : null)
                        .busNumber(v.getBusNumber())
                        .lat(v.getLat())
                        .lng(v.getLng())
                        .lastSeen(v.getLastSeen())
                        .build())
                .toList();
    }

    // ── Маршрути ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminRouteResponseDto> getAllRoutes() {
        // Рахуємо онлайн-транспорт по маршрутах одним запитом
        Map<Long, Long> countByRoute = vehicleRepository.findByIsOnlineTrue()
                .stream()
                .filter(v -> v.getRoute() != null)
                .collect(Collectors.groupingBy(
                        v -> v.getRoute().getId(),
                        Collectors.counting()));

        return routeRepository.findAll().stream()
                .map(r -> AdminRouteResponseDto.builder()
                        .id(r.getId())
                        .name(r.getName())
                        .type(r.getType().name())
                        .color(r.getColor())
                        .isActive(r.getIsActive())
                        .vehicleCount(countByRoute.getOrDefault(r.getId(), 0L).intValue())
                        .updatedAt(r.getUpdatedAt())
                        .build())
                .toList();
    }

    @Transactional
    public AdminRouteResponseDto createRoute(AdminRouteRequestDto req) {
        TransportType type = parseType(req.getType());
        Route route = routeRepository.save(Route.builder()
                .name(req.getName())
                .type(type)
                .color(req.getColor())
                .isActive(true)
                .build());

        log.info("Admin: created route id={} name='{}' type={}", route.getId(), route.getName(), type);
        return toAdminRouteDto(route, 0);
    }

    @Transactional
    public AdminRouteResponseDto updateRoute(Long id, AdminRouteRequestDto req) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + id));

        if (req.getName()  != null) route.setName(req.getName());
        if (req.getType()  != null) route.setType(parseType(req.getType()));
        if (req.getColor() != null) route.setColor(req.getColor());
        if (req.getIsActive() != null) route.setIsActive(req.getIsActive());

        routeRepository.save(route);
        log.info("Admin: updated route id={}", id);

        int count = vehicleRepository.findByRouteIdAndIsOnlineTrue(id).size();
        return toAdminRouteDto(route, count);
    }

    @Transactional
    public void deactivateRoute(Long id) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + id));
        route.setIsActive(false);
        routeRepository.save(route);
        log.info("Admin: deactivated route id={} name='{}'", id, route.getName());
    }

    // ── Source Mappings ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SourceMappingResponseDto> getMappings(String source, String entityType) {
        return sourceMappingRepository.findAll().stream()
                .filter(m -> source     == null || m.getSource().name().equalsIgnoreCase(source))
                .filter(m -> entityType == null || m.getEntityType().equalsIgnoreCase(entityType))
                .map(m -> SourceMappingResponseDto.builder()
                        .id(m.getId())
                        .entityType(m.getEntityType())
                        .canonicalId(m.getCanonicalId())
                        .source(m.getSource().name())
                        .sourceId(m.getSourceId())
                        .build())
                .toList();
    }

    @Transactional
    public void deleteMapping(Long id) {
        if (!sourceMappingRepository.existsById(id)) {
            throw new IllegalArgumentException("SourceMapping not found: " + id);
        }
        sourceMappingRepository.deleteById(id);
        log.info("Admin: deleted source_mapping id={}", id);
    }

    // ── Кеш ───────────────────────────────────────────────────────────────

    @CacheEvict(value = {CACHE_ROUTES, CACHE_VEHICLES}, allEntries = true)
    public CacheEvictResponseDto evictAllCaches() {
        log.info("Admin: cache evicted manually");
        return CacheEvictResponseDto.builder()
                .evictedCaches(List.of(CACHE_ROUTES, CACHE_VEHICLES))
                .evictedAt(OffsetDateTime.now())
                .build();
    }

    // ── Статус колекторів ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CollectorStatusDto> getCollectorsStatus() {
        // Рахуємо кількість онлайн-транспорту по кожному джерелу
        Map<String, Long> countBySource = vehicleRepository.findByIsOnlineTrue()
                .stream()
                .collect(Collectors.groupingBy(
                        v -> v.getSource().name(),
                        Collectors.counting()));

        // Час останнього запису в gps_history по кожному джерелу
        Map<String, OffsetDateTime> lastSeenBySource = vehicleRepository.findByIsOnlineTrue()
                .stream()
                .collect(Collectors.toMap(
                        v -> v.getSource().name(),
                        v -> v.getLastSeen(),
                        (a, b) -> a.isAfter(b) ? a : b));

        return Arrays.stream(DataSource.values())
                .map(src -> CollectorStatusDto.builder()
                        .source(src.name())
                        .onlineVehicles(countBySource.getOrDefault(src.name(), 0L).intValue())
                        .lastReceivedAt(lastSeenBySource.get(src.name()))
                        .build())
                .toList();
    }

    // ── Утиліти ───────────────────────────────────────────────────────────

    private TransportType parseType(String type) {
        if (type == null) return TransportType.BUS;
        try {
            return TransportType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown transport type: " + type +
                    ". Valid values: BUS, TROLL, TRAM, TAXI, DEFAULT");
        }
    }

    private AdminRouteResponseDto toAdminRouteDto(Route r, int vehicleCount) {
        return AdminRouteResponseDto.builder()
                .id(r.getId())
                .name(r.getName())
                .type(r.getType().name())
                .color(r.getColor())
                .isActive(r.getIsActive())
                .vehicleCount(vehicleCount)
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}