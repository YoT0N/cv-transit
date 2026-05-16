package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.*;
import edu.ilkiv.transit.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VehicleAggregationService — unit tests")
class VehicleAggregationServiceTest {

    @Mock VehicleRepository vehicleRepository;
    @Mock RouteRepository routeRepository;
    @Mock SourceMappingRepository sourceMappingRepository;
    @Mock GpsHistoryRepository gpsHistoryRepository;
    @Mock VehicleBroadcastService broadcastService;
    @Mock VehicleService vehicleService;

    @InjectMocks
    VehicleAggregationService aggregationService;

    // ── Спільні фікстури ──────────────────────────────────────────────────────

    private VehiclePositionDto buildDto(String externalId, DataSource source,
                                        String routeName, String externalRouteId) {
        return VehiclePositionDto.builder()
                .externalId(externalId)
                .source(source)
                .routeName(routeName)
                .externalRouteId(externalRouteId)
                .type(TransportType.BUS)
                .lat(48.29)
                .lng(25.93)
                .speed(30f)
                .bearing(90f)
                .busNumber("1234")
                .online(true)
                .build();
    }

    private Route buildRoute(Long id, String name) {
        Route r = new Route();
        r.setId(id);
        r.setName(name);
        r.setType(TransportType.BUS);
        r.setIsActive(true);
        return r;
    }

    private Vehicle buildVehicle(Long id, String externalId, DataSource source) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setExternalId(externalId);
        v.setSource(source);
        v.setLat(48.0);
        v.setLng(25.0);
        return v;
    }

    @BeforeEach
    void setUp() {
        // За замовчуванням save повертає те саме що отримав
        when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(routeRepository.save(any())).thenAnswer(inv -> {
            Route r = inv.getArgument(0);
            if (r.getId() == null) r.setId(99L); // імітуємо BIGSERIAL
            return r;
        });
        when(gpsHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sourceMappingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Тест 1: новий vehicle, новий маршрут ──────────────────────────────────

    @Test
    @DisplayName("Новий vehicle і новий маршрут — мають бути збережені в БД")
    void processPositions_newVehicleAndRoute_savedToDB() {
        // given
        VehiclePositionDto dto = buildDto("imei-001", DataSource.transgps, "10", "route-10");

        when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(
                "route", "transgps", "route-10"))
                .thenReturn(Optional.empty());
        when(routeRepository.findByName("10")).thenReturn(Optional.empty());
        when(vehicleRepository.findByExternalIdAndSource("imei-001", DataSource.transgps))
                .thenReturn(Optional.empty());

        // when
        aggregationService.processPositions(List.of(dto));

        // then — маршрут створено
        ArgumentCaptor<Route> routeCaptor = ArgumentCaptor.forClass(Route.class);
        verify(routeRepository).save(routeCaptor.capture());
        assertThat(routeCaptor.getValue().getName()).isEqualTo("10");

        // then — vehicle збережено з правильними координатами
        ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicleRepository, atLeastOnce()).save(vehicleCaptor.capture());
        Vehicle saved = vehicleCaptor.getValue();
        assertThat(saved.getLat()).isEqualTo(48.29);
        assertThat(saved.getLng()).isEqualTo(25.93);
        assertThat(saved.getIsOnline()).isTrue();

        // then — GPS точка збережена
        verify(gpsHistoryRepository, atLeastOnce()).save(any(GpsHistory.class));

        // then — broadcast викликано
        verify(broadcastService).broadcast(anyList());
    }

    // ── Тест 2: існуючий vehicle оновлюється ─────────────────────────────────

    @Test
    @DisplayName("Існуючий vehicle — позиція оновлюється, дублікат не створюється")
    void processPositions_existingVehicle_positionUpdated() {
        // given
        VehiclePositionDto dto = buildDto("imei-002", DataSource.transgps, "20", "route-20");
        Route existingRoute = buildRoute(5L, "20");
        Vehicle existingVehicle = buildVehicle(42L, "imei-002", DataSource.transgps);
        existingVehicle.setRoute(existingRoute);

        SourceMapping mapping = new SourceMapping();
        mapping.setCanonicalId(5L);

        when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(
                "route", "transgps", "route-20"))
                .thenReturn(Optional.of(mapping));
        when(routeRepository.findById(5L)).thenReturn(Optional.of(existingRoute));
        when(vehicleRepository.findByExternalIdAndSource("imei-002", DataSource.transgps))
                .thenReturn(Optional.of(existingVehicle));

        // when
        aggregationService.processPositions(List.of(dto));

        // then — новий маршрут НЕ створювався
        verify(routeRepository, never()).save(any(Route.class));

        // then — координати оновлені
        assertThat(existingVehicle.getLat()).isEqualTo(48.29);
        assertThat(existingVehicle.getLng()).isEqualTo(25.93);
        assertThat(existingVehicle.getSpeed()).isEqualTo(30f);
    }

    // ── Тест 3: vehicle без маршруту ─────────────────────────────────────────

    @Test
    @DisplayName("Vehicle без externalRouteId і routeName — зберігається з route=null")
    void processPositions_noRoute_vehicleSavedWithNullRoute() {
        // given
        VehiclePositionDto dto = VehiclePositionDto.builder()
                .externalId("imei-003")
                .source(DataSource.nimbus)
                .type(TransportType.BUS)
                .lat(48.30)
                .lng(25.94)
                .speed(0f)
                .bearing(0f)
                .online(true)
                .build(); // externalRouteId та routeName = null

        when(vehicleRepository.findByExternalIdAndSource("imei-003", DataSource.nimbus))
                .thenReturn(Optional.empty());

        // when
        aggregationService.processPositions(List.of(dto));

        // then — route не шукався і не створювався
        verify(routeRepository, never()).findByName(any());
        verify(routeRepository, never()).save(any());

        // then — vehicle збережено
        verify(vehicleRepository, atLeastOnce()).save(any(Vehicle.class));
    }

    // ── Тест 4: батч з кількох vehicles ──────────────────────────────────────

    @Test
    @DisplayName("Батч з 3 vehicles — broadcast викликається один раз")
    void processPositions_batchOf3_broadcastCalledOnce() {
        // given
        Route route = buildRoute(1L, "5");
        SourceMapping mapping = new SourceMapping();
        mapping.setCanonicalId(1L);

        when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(
                eq("route"), eq("transgps"), anyString()))
                .thenReturn(Optional.of(mapping));
        when(routeRepository.findById(1L)).thenReturn(Optional.of(route));
        when(vehicleRepository.findByExternalIdAndSource(anyString(), any()))
                .thenReturn(Optional.empty());

        List<VehiclePositionDto> batch = List.of(
                buildDto("v1", DataSource.transgps, "5", "r5"),
                buildDto("v2", DataSource.transgps, "5", "r5"),
                buildDto("v3", DataSource.transgps, "5", "r5")
        );

        // when
        aggregationService.processPositions(batch);

        // then — broadcast рівно один раз для всього батчу
        verify(broadcastService, times(1)).broadcast(anyList());

        // then — кеш інвалідовано
        verify(vehicleService, times(1)).evictCache();
    }

    // ── Тест 5: порожній список — нічого не відбувається ─────────────────────

    @Test
    @DisplayName("Порожній список positions — жодних взаємодій з БД")
    void processPositions_emptyList_noDbInteractions() {
        // when
        aggregationService.processPositions(List.of());

        // then
        verifyNoInteractions(vehicleRepository, routeRepository,
                gpsHistoryRepository, broadcastService);
    }

    // ── Тест 6: offline vehicle ───────────────────────────────────────────────

    @Test
    @DisplayName("Vehicle з online=false — isOnline встановлюється в false")
    void processPositions_offlineVehicle_isOnlineFalse() {
        // given
        VehiclePositionDto dto = VehiclePositionDto.builder()
                .externalId("imei-offline")
                .source(DataSource.transgps)
                .routeName("7")
                .externalRouteId("r7")
                .type(TransportType.BUS)
                .lat(48.28)
                .lng(25.92)
                .speed(0f)
                .bearing(0f)
                .online(false) // ← офлайн
                .build();

        when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(routeRepository.findByName("7")).thenReturn(Optional.empty());
        when(vehicleRepository.findByExternalIdAndSource(any(), any()))
                .thenReturn(Optional.empty());

        // when
        aggregationService.processPositions(List.of(dto));

        // then
        ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicleRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getIsOnline()).isFalse();
    }
}