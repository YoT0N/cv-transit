package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.RouteResponseDto;
import edu.ilkiv.transit.model.Route;
import edu.ilkiv.transit.model.TransportType;
import edu.ilkiv.transit.model.Vehicle;
import edu.ilkiv.transit.repository.RouteRepository;
import edu.ilkiv.transit.repository.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RouteService — unit tests")
class RouteServiceTest {

    @Mock RouteRepository routeRepository;
    @Mock VehicleRepository vehicleRepository;

    @InjectMocks RouteService routeService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Route buildRoute(Long id, String name, TransportType type, boolean active) {
        Route r = new Route();
        r.setId(id);
        r.setName(name);
        r.setType(type);
        r.setColor("#FF0000");
        r.setIsActive(active);
        return r;
    }

    private Vehicle buildVehicle(Long id) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setLat(48.0);
        v.setLng(25.0);
        v.setIsOnline(true);
        return v;
    }

    // ── getAllActive ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getAllActive — повертає DTO для кожного активного маршруту")
    void getAllActive_returnsAllActiveRoutes() {
        Route r1 = buildRoute(1L, "10", TransportType.BUS, true);
        Route r2 = buildRoute(2L, "1",  TransportType.TROLL, true);

        when(routeRepository.findByIsActiveTrue()).thenReturn(List.of(r1, r2));
        when(vehicleRepository.findByRouteIdAndIsOnlineTrue(1L)).thenReturn(List.of(buildVehicle(10L)));
        when(vehicleRepository.findByRouteIdAndIsOnlineTrue(2L)).thenReturn(Collections.emptyList());

        List<RouteResponseDto> result = routeService.getAllActive();

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("getAllActive — vehicleCount правильно підраховується")
    void getAllActive_vehicleCount_isCorrect() {
        Route route = buildRoute(5L, "3", TransportType.BUS, true);
        when(routeRepository.findByIsActiveTrue()).thenReturn(List.of(route));
        when(vehicleRepository.findByRouteIdAndIsOnlineTrue(5L))
                .thenReturn(List.of(buildVehicle(1L), buildVehicle(2L), buildVehicle(3L)));

        List<RouteResponseDto> result = routeService.getAllActive();

        assertThat(result.get(0).getVehicleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("getAllActive — маппінг полів у DTO коректний")
    void getAllActive_fieldMapping_correct() {
        Route route = buildRoute(7L, "19", TransportType.BUS, true);
        route.setColor("#1E88E5");

        when(routeRepository.findByIsActiveTrue()).thenReturn(List.of(route));
        when(vehicleRepository.findByRouteIdAndIsOnlineTrue(7L)).thenReturn(List.of(buildVehicle(99L)));

        RouteResponseDto dto = routeService.getAllActive().get(0);

        assertThat(dto.getId()).isEqualTo(7L);
        assertThat(dto.getName()).isEqualTo("19");
        assertThat(dto.getType()).isEqualTo(TransportType.BUS);
        assertThat(dto.getColor()).isEqualTo("#1E88E5");
        assertThat(dto.getIsActive()).isTrue();
        assertThat(dto.getVehicleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("getAllActive — порожній репозиторій → порожній список")
    void getAllActive_emptyRepository_returnsEmpty() {
        when(routeRepository.findByIsActiveTrue()).thenReturn(Collections.emptyList());

        List<RouteResponseDto> result = routeService.getAllActive();

        assertThat(result).isEmpty();
        verifyNoInteractions(vehicleRepository);
    }

    @Test
    @DisplayName("getAllActive — маршрут без транспорту має vehicleCount = 0")
    void getAllActive_routeWithNoVehicles_vehicleCountZero() {
        Route route = buildRoute(2L, "7", TransportType.BUS, true);
        when(routeRepository.findByIsActiveTrue()).thenReturn(List.of(route));
        when(vehicleRepository.findByRouteIdAndIsOnlineTrue(2L)).thenReturn(Collections.emptyList());

        RouteResponseDto dto = routeService.getAllActive().get(0);

        assertThat(dto.getVehicleCount()).isEqualTo(0);
    }
}