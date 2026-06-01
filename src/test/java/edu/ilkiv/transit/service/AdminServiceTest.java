package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.admin.*;
import edu.ilkiv.transit.model.*;
import edu.ilkiv.transit.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminService — unit tests")
class AdminServiceTest {

    @Mock VehicleRepository vehicleRepository;
    @Mock RouteRepository routeRepository;
    @Mock GpsHistoryRepository gpsHistoryRepository;
    @Mock SourceMappingRepository sourceMappingRepository;

    @InjectMocks AdminService adminService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Route buildRoute(Long id, String name, TransportType type, boolean active) {
        Route r = new Route();
        r.setId(id);
        r.setName(name);
        r.setType(type);
        r.setColor("#FF0000");
        r.setIsActive(active);
        r.setUpdatedAt(OffsetDateTime.now());
        return r;
    }

    private Vehicle buildVehicle(Long id, DataSource source, Route route) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setExternalId("ext-" + id);
        v.setSource(source);
        v.setRoute(route);
        v.setBusNumber("B" + id);
        v.setLat(48.29);
        v.setLng(25.93);
        v.setIsOnline(true);
        v.setLastSeen(OffsetDateTime.now());
        return v;
    }

    private Vehicle buildOfflineVehicle(Long id) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setExternalId("ext-offline-" + id);
        v.setSource(DataSource.transgps);
        v.setBusNumber("OFF" + id);
        v.setLat(48.0);
        v.setLng(25.0);
        v.setIsOnline(false);
        v.setLastSeen(OffsetDateTime.now().minusHours(2));
        return v;
    }

    private GpsHistory buildGpsHistory(Long id, Vehicle vehicle) {
        GpsHistory h = new GpsHistory();
        h.setId(id);
        h.setVehicle(vehicle);
        h.setLat(48.29);
        h.setLng(25.93);
        h.setSpeed(30f);
        h.setSource(DataSource.transgps);
        h.setRecordedAt(OffsetDateTime.now().minusMinutes(10));
        return h;
    }

    private SourceMapping buildMapping(Long id, String entityType, Long canonicalId,
                                       DataSource source, String sourceId) {
        SourceMapping m = new SourceMapping();
        m.setId(id);
        m.setEntityType(entityType);
        m.setCanonicalId(canonicalId);
        m.setSource(source);
        m.setSourceId(sourceId);
        return m;
    }

    // ── getVehicleHistory ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getVehicleHistory")
    class GetVehicleHistoryTests {

        @Test
        @DisplayName("Повертає GPS-трек за вказану кількість годин")
        void getVehicleHistory_returnsHistory() {
            Vehicle vehicle = buildVehicle(1L, DataSource.transgps, null);
            GpsHistory h1 = buildGpsHistory(10L, vehicle);
            GpsHistory h2 = buildGpsHistory(11L, vehicle);

            when(gpsHistoryRepository.findByVehicleIdAndRecordedAtAfterOrderByRecordedAtAsc(
                    eq(1L), any(OffsetDateTime.class)))
                    .thenReturn(List.of(h1, h2));

            List<GpsHistoryResponseDto> result = adminService.getVehicleHistory(1L, 24);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getVehicleId()).isEqualTo(1L);
            assertThat(result.get(0).getLat()).isEqualTo(48.29);
            assertThat(result.get(0).getSource()).isEqualTo("transgps");
        }

        @Test
        @DisplayName("Порожня історія — повертає порожній список")
        void getVehicleHistory_empty_returnsEmpty() {
            when(gpsHistoryRepository.findByVehicleIdAndRecordedAtAfterOrderByRecordedAtAsc(
                    anyLong(), any())).thenReturn(Collections.emptyList());

            assertThat(adminService.getVehicleHistory(99L, 24)).isEmpty();
        }
    }

    // ── getOfflineVehicles ────────────────────────────────────────────────────

    @Nested
    @DisplayName("getOfflineVehicles")
    class GetOfflineVehiclesTests {

        @Test
        @DisplayName("Повертає тільки офлайн vehicles")
        void getOfflineVehicles_returnsOnlyOffline() {
            Vehicle online  = buildVehicle(1L, DataSource.transgps, null);
            Vehicle offline = buildOfflineVehicle(2L);

            when(vehicleRepository.findAll()).thenReturn(List.of(online, offline));

            List<OfflineVehicleResponseDto> result = adminService.getOfflineVehicles();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("Немає офлайн vehicles — повертає порожній список")
        void getOfflineVehicles_noneOffline_returnsEmpty() {
            when(vehicleRepository.findAll())
                    .thenReturn(List.of(buildVehicle(1L, DataSource.transgps, null)));

            assertThat(adminService.getOfflineVehicles()).isEmpty();
        }

        @Test
        @DisplayName("Офлайн vehicle з маршрутом — routeName заповнено")
        void getOfflineVehicles_withRoute_routeNameFilled() {
            Route route = buildRoute(1L, "10", TransportType.BUS, true);
            Vehicle offline = buildOfflineVehicle(5L);
            offline.setRoute(route);

            when(vehicleRepository.findAll()).thenReturn(List.of(offline));

            List<OfflineVehicleResponseDto> result = adminService.getOfflineVehicles();

            assertThat(result.get(0).getRouteName()).isEqualTo("10");
        }
    }

    // ── getAllRoutes ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAllRoutes")
    class GetAllRoutesTests {

        @Test
        @DisplayName("Повертає всі маршрути включно з неактивними")
        void getAllRoutes_returnsAllIncludingInactive() {
            Route active   = buildRoute(1L, "10", TransportType.BUS, true);
            Route inactive = buildRoute(2L, "99", TransportType.BUS, false);

            when(routeRepository.findAll()).thenReturn(List.of(active, inactive));
            when(vehicleRepository.findByIsOnlineTrue()).thenReturn(Collections.emptyList());

            List<AdminRouteResponseDto> result = adminService.getAllRoutes();

            assertThat(result).hasSize(2);
            assertThat(result.stream().anyMatch(r -> !r.getIsActive())).isTrue();
        }

        @Test
        @DisplayName("vehicleCount рахується правильно")
        void getAllRoutes_vehicleCount_correct() {
            Route route = buildRoute(1L, "10", TransportType.BUS, true);
            Vehicle v1 = buildVehicle(1L, DataSource.transgps, route);
            Vehicle v2 = buildVehicle(2L, DataSource.transgps, route);

            when(routeRepository.findAll()).thenReturn(List.of(route));
            when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(v1, v2));

            List<AdminRouteResponseDto> result = adminService.getAllRoutes();

            assertThat(result.get(0).getVehicleCount()).isEqualTo(2);
        }
    }

    // ── createRoute ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createRoute")
    class CreateRouteTests {

        @Test
        @DisplayName("Створює маршрут з правильними полями")
        void createRoute_savesCorrectRoute() {
            AdminRouteRequestDto req = new AdminRouteRequestDto();
            req.setName("42");
            req.setType("BUS");
            req.setColor("#FF0000");

            Route saved = buildRoute(10L, "42", TransportType.BUS, true);
            when(routeRepository.save(any(Route.class))).thenReturn(saved);

            AdminRouteResponseDto result = adminService.createRoute(req);

            assertThat(result.getName()).isEqualTo("42");
            assertThat(result.getType()).isEqualTo("BUS");

            ArgumentCaptor<Route> captor = ArgumentCaptor.forClass(Route.class);
            verify(routeRepository).save(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("42");
        }

        @Test
        @DisplayName("Невідомий тип транспорту — кидає IllegalArgumentException")
        void createRoute_unknownType_throwsException() {
            AdminRouteRequestDto req = new AdminRouteRequestDto();
            req.setName("X");
            req.setType("UNKNOWN_TYPE");

            assertThatThrownBy(() -> adminService.createRoute(req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown transport type");
        }

        @Test
        @DisplayName("null тип — використовується BUS за замовчуванням")
        void createRoute_nullType_defaultsBus() {
            AdminRouteRequestDto req = new AdminRouteRequestDto();
            req.setName("5");
            req.setType(null);

            Route saved = buildRoute(1L, "5", TransportType.BUS, true);
            when(routeRepository.save(any())).thenReturn(saved);

            adminService.createRoute(req);

            ArgumentCaptor<Route> captor = ArgumentCaptor.forClass(Route.class);
            verify(routeRepository).save(captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(TransportType.BUS);
        }

        @Test
        @DisplayName("Тип TROLL — зберігається правильно")
        void createRoute_trollType_savedCorrectly() {
            AdminRouteRequestDto req = new AdminRouteRequestDto();
            req.setName("3");
            req.setType("TROLL");

            Route saved = buildRoute(2L, "3", TransportType.TROLL, true);
            when(routeRepository.save(any())).thenReturn(saved);

            AdminRouteResponseDto result = adminService.createRoute(req);

            assertThat(result.getType()).isEqualTo("TROLL");
        }
    }

    // ── updateRoute ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateRoute")
    class UpdateRouteTests {

        @Test
        @DisplayName("Оновлює існуючий маршрут")
        void updateRoute_existingRoute_updated() {
            Route existing = buildRoute(1L, "10", TransportType.BUS, true);
            AdminRouteRequestDto req = new AdminRouteRequestDto();
            req.setName("10A");
            req.setColor("#0000FF");
            req.setIsActive(false);

            when(routeRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(routeRepository.save(any())).thenReturn(existing);
            when(vehicleRepository.findByRouteIdAndIsOnlineTrue(1L))
                    .thenReturn(Collections.emptyList());

            AdminRouteResponseDto result = adminService.updateRoute(1L, req);

            assertThat(existing.getName()).isEqualTo("10A");
            assertThat(existing.getColor()).isEqualTo("#0000FF");
            assertThat(existing.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("Маршрут не знайдено — кидає IllegalArgumentException")
        void updateRoute_notFound_throwsException() {
            when(routeRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminService.updateRoute(999L, new AdminRouteRequestDto()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Route not found");
        }
    }

    // ── deactivateRoute ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("deactivateRoute")
    class DeactivateRouteTests {

        @Test
        @DisplayName("Встановлює isActive = false")
        void deactivateRoute_setsInactive() {
            Route route = buildRoute(1L, "10", TransportType.BUS, true);
            when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
            when(routeRepository.save(any())).thenReturn(route);

            adminService.deactivateRoute(1L);

            assertThat(route.getIsActive()).isFalse();
            verify(routeRepository).save(route);
        }

        @Test
        @DisplayName("Маршрут не знайдено — кидає IllegalArgumentException")
        void deactivateRoute_notFound_throwsException() {
            when(routeRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminService.deactivateRoute(999L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── getMappings ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMappings")
    class GetMappingsTests {

        @Test
        @DisplayName("Без фільтрів — повертає всі mappings")
        void getMappings_noFilter_returnsAll() {
            when(sourceMappingRepository.findAll()).thenReturn(List.of(
                    buildMapping(1L, "route", 10L, DataSource.transgps, "r1"),
                    buildMapping(2L, "route", 11L, DataSource.nimbus, "r2")
            ));

            List<SourceMappingResponseDto> result = adminService.getMappings(null, null);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Фільтр по source — повертає тільки відповідні")
        void getMappings_sourceFilter_filtered() {
            when(sourceMappingRepository.findAll()).thenReturn(List.of(
                    buildMapping(1L, "route", 10L, DataSource.transgps, "r1"),
                    buildMapping(2L, "route", 11L, DataSource.nimbus, "r2")
            ));

            List<SourceMappingResponseDto> result = adminService.getMappings("transgps", null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSource()).isEqualTo("transgps");
        }

        @Test
        @DisplayName("Фільтр по entityType — повертає тільки відповідні")
        void getMappings_entityTypeFilter_filtered() {
            when(sourceMappingRepository.findAll()).thenReturn(List.of(
                    buildMapping(1L, "route", 10L, DataSource.transgps, "r1"),
                    buildMapping(2L, "stop", 20L, DataSource.transgps, "s1")
            ));

            List<SourceMappingResponseDto> result = adminService.getMappings(null, "route");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getEntityType()).isEqualTo("route");
        }
    }

    // ── deleteMapping ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteMapping")
    class DeleteMappingTests {

        @Test
        @DisplayName("Існуючий mapping — видаляється")
        void deleteMapping_existing_deleted() {
            when(sourceMappingRepository.existsById(1L)).thenReturn(true);

            adminService.deleteMapping(1L);

            verify(sourceMappingRepository).deleteById(1L);
        }

        @Test
        @DisplayName("Не існує — кидає IllegalArgumentException")
        void deleteMapping_notFound_throwsException() {
            when(sourceMappingRepository.existsById(999L)).thenReturn(false);

            assertThatThrownBy(() -> adminService.deleteMapping(999L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SourceMapping not found");
        }
    }

    // ── getCollectorsStatus ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getCollectorsStatus")
    class GetCollectorsStatusTests {

        @Test
        @DisplayName("Повертає статус для всіх джерел DataSource")
        void getCollectorsStatus_returnsAllSources() {
            Route route = buildRoute(1L, "10", TransportType.BUS, true);
            Vehicle v = buildVehicle(1L, DataSource.transgps, route);

            when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(v));

            List<CollectorStatusDto> result = adminService.getCollectorsStatus();

            assertThat(result).hasSize(DataSource.values().length);
            assertThat(result.stream().anyMatch(s -> s.getSource().equals("transgps"))).isTrue();
        }

        @Test
        @DisplayName("Джерело без vehicles — onlineVehicles = 0")
        void getCollectorsStatus_noVehicles_zeroCount() {
            when(vehicleRepository.findByIsOnlineTrue()).thenReturn(Collections.emptyList());

            List<CollectorStatusDto> result = adminService.getCollectorsStatus();

            assertThat(result).allMatch(s -> s.getOnlineVehicles() == 0);
        }
    }

    // ── evictAllCaches ────────────────────────────────────────────────────────

    @Test
    @DisplayName("evictAllCaches — повертає список інвалідованих кешів")
    void evictAllCaches_returnsEvictedCacheNames() {
        CacheEvictResponseDto result = adminService.evictAllCaches();

        assertThat(result.getEvictedCaches()).contains("routes", "vehicles");
        assertThat(result.getEvictedAt()).isNotNull();
    }
}