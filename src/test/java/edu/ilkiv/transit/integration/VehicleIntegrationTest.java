package edu.ilkiv.transit.integration;

import edu.ilkiv.transit.model.*;
import edu.ilkiv.transit.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
@DisplayName("Vehicle — integration tests")
class VehicleIntegrationTest {

    @Autowired VehicleRepository vehicleRepository;
    @Autowired RouteRepository routeRepository;

    @Test
    @DisplayName("Збереження та пошук vehicle по externalId і source")
    void saveAndFind_byExternalIdAndSource() {
        Route route = routeRepository.save(Route.builder()
                .name("10")
                .type(TransportType.BUS)
                .isActive(true)
                .build());

        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .externalId("test-imei-001")
                .source(DataSource.transgps)
                .route(route)
                .lat(48.29)
                .lng(25.93)
                .isOnline(true)
                .build());

        var found = vehicleRepository
                .findByExternalIdAndSource("test-imei-001", DataSource.transgps);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(vehicle.getId());
        assertThat(found.get().getRoute().getName()).isEqualTo("10");
    }

    @Test
    @DisplayName("findNearby — знаходить vehicle в радіусі 1 км")
    void findNearby_vehicleInRadius_found() {
        vehicleRepository.save(Vehicle.builder()
                .externalId("nearby-001")
                .source(DataSource.transgps)
                .lat(48.2921)
                .lng(25.9358)
                .isOnline(true)
                .build());

        var result = vehicleRepository.findNearby(48.2921, 25.9358, 1.0);

        assertThat(result).isNotEmpty();
        assertThat(result.stream()
                .anyMatch(v -> v.getExternalId().equals("nearby-001")))
                .isTrue();
    }

    @Test
    @DisplayName("findNearby — не знаходить vehicle поза радіусом")
    void findNearby_vehicleOutOfRadius_notFound() {
        vehicleRepository.save(Vehicle.builder()
                .externalId("far-001")
                .source(DataSource.transgps)
                .lat(50.45) // Київ
                .lng(30.52)
                .isOnline(true)
                .build());

        // Шукаємо в Чернівцях
        var result = vehicleRepository.findNearby(48.2921, 25.9358, 1.0);

        assertThat(result.stream()
                .anyMatch(v -> v.getExternalId().equals("far-001")))
                .isFalse();
    }
}