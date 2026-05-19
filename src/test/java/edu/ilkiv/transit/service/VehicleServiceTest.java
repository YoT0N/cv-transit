package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.VehicleResponseDto;
import edu.ilkiv.transit.model.*;
import edu.ilkiv.transit.repository.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VehicleService — unit tests")
class VehicleServiceTest {

    @Mock VehicleRepository vehicleRepository;

    @InjectMocks VehicleService vehicleService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Vehicle buildVehicle(Long id, String routeName, TransportType type) {
        Route route = new Route();
        route.setId(10L);
        route.setName(routeName);
        route.setType(type);
        route.setColor("#FF5722");
        route.setIsActive(true);

        Vehicle v = new Vehicle();
        v.setId(id);
        v.setExternalId("ext-" + id);
        v.setSource(DataSource.transgps);
        v.setRoute(route);
        v.setBusNumber("B" + id);
        v.setLat(48.29);
        v.setLng(25.93);
        v.setSpeed(30f);
        v.setBearing(90f);
        v.setIsOnline(true);
        v.setLastSeen(OffsetDateTime.now());
        return v;
    }

    private Vehicle buildVehicleNoRoute(Long id) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setExternalId("ext-" + id);
        v.setSource(DataSource.nimbus);
        v.setLat(48.28);
        v.setLng(25.94);
        v.setIsOnline(true);
        v.setLastSeen(OffsetDateTime.now());
        return v;
    }

    // ── getAllOnline ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getAllOnline — повертає список DTO для всіх онлайн vehicle")
    void getAllOnline_returnsAllOnline() {
        when(vehicleRepository.findByIsOnlineTrue())
                .thenReturn(List.of(
                        buildVehicle(1L, "10", TransportType.BUS),
                        buildVehicle(2L, "1", TransportType.TROLL)
                ));

        List<VehicleResponseDto> result = vehicleService.getAllOnline();

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("getAllOnline — маппінг полів у DTO коректний")
    void getAllOnline_fieldMapping_correct() {
        Vehicle v = buildVehicle(42L, "9A", TransportType.BUS);
        when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(v));

        VehicleResponseDto dto = vehicleService.getAllOnline().get(0);

        assertThat(dto.getId()).isEqualTo(42L);
        assertThat(dto.getRouteName()).isEqualTo("9A");
        assertThat(dto.getRouteColor()).isEqualTo("#FF5722");
        assertThat(dto.getType()).isEqualTo(TransportType.BUS);
        assertThat(dto.getLat()).isEqualTo(48.29);
        assertThat(dto.getLng()).isEqualTo(25.93);
        assertThat(dto.getSpeed()).isEqualTo(30f);
        assertThat(dto.getBearing()).isEqualTo(90f);
        assertThat(dto.getBusNumber()).isEqualTo("B42");
        assertThat(dto.getOnline()).isTrue();
        assertThat(dto.getLastSeen()).isNotNull();
    }

    @Test
    @DisplayName("getAllOnline — vehicle без маршруту: routeName і routeColor = null")
    void getAllOnline_vehicleWithoutRoute_nullRouteFields() {
        when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(buildVehicleNoRoute(5L)));

        VehicleResponseDto dto = vehicleService.getAllOnline().get(0);

        assertThat(dto.getRouteName()).isNull();
        assertThat(dto.getRouteColor()).isNull();
        assertThat(dto.getType()).isNull();
    }

    @Test
    @DisplayName("getAllOnline — порожній результат повертає порожній список")
    void getAllOnline_empty_returnsEmptyList() {
        when(vehicleRepository.findByIsOnlineTrue()).thenReturn(Collections.emptyList());

        assertThat(vehicleService.getAllOnline()).isEmpty();
    }

    // ── getByRoute ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getByRoute — повертає тільки vehicle даного маршруту")
    void getByRoute_returnsCorrectVehicles() {
        Long routeId = 3L;
        when(vehicleRepository.findByRouteIdAndIsOnlineTrue(routeId))
                .thenReturn(List.of(buildVehicle(7L, "3", TransportType.BUS)));

        List<VehicleResponseDto> result = vehicleService.getByRoute(routeId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRouteName()).isEqualTo("3");
    }

    @Test
    @DisplayName("getByRoute — маршрут без активного транспорту повертає порожній список")
    void getByRoute_noVehicles_returnsEmpty() {
        when(vehicleRepository.findByRouteIdAndIsOnlineTrue(99L))
                .thenReturn(Collections.emptyList());

        assertThat(vehicleService.getByRoute(99L)).isEmpty();
    }

    // ── getNearby ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getNearby — повертає vehicle у вказаному радіусі")
    void getNearby_returnsVehiclesInRadius() {
        double lat = 48.29, lng = 25.93, radius = 0.5;
        when(vehicleRepository.findNearby(lat, lng, radius))
                .thenReturn(List.of(
                        buildVehicle(10L, "8", TransportType.BUS),
                        buildVehicle(11L, "2", TransportType.TROLL)
                ));

        List<VehicleResponseDto> result = vehicleService.getNearby(lat, lng, radius);

        assertThat(result).hasSize(2);
        verify(vehicleRepository).findNearby(lat, lng, radius);
    }

    @Test
    @DisplayName("getNearby — порожній результат якщо нічого поруч")
    void getNearby_nothingNearby_returnsEmpty() {
        when(vehicleRepository.findNearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Collections.emptyList());

        assertThat(vehicleService.getNearby(0.0, 0.0, 0.1)).isEmpty();
    }
}