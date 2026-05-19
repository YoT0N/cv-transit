package edu.ilkiv.transit.collector;

import edu.ilkiv.transit.dto.TransportCvResponseDto;
import edu.ilkiv.transit.dto.TransportCvVehicleDto;
import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.TransportType;
import edu.ilkiv.transit.service.VehicleAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransportCvCollector — unit tests")
class TransportCvCollectorTest {

    @Mock
    private VehicleAggregationService aggregationService;

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private TransportCvCollector collector;

    @BeforeEach
    void setUp() {
        // Створюємо collector з WebClient (не Builder)
        collector = new TransportCvCollector(webClient, aggregationService);
    }

    @Nested
    @DisplayName("toPositionDto — маппінг полів (через reflection)")
    class ToPositionDtoTests {

        @Test
        @DisplayName("Відомі rtsId правильно мапляться на routeName")
        void toPositionDto_knownRtsId_mapsRouteName() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(121L, "5453", 48.27547, 25.92966, 30, 45);

            VehiclePositionDto result = invokeToPositionDto(dto);

            assertThat(result.getRouteName()).isEqualTo("3");
            assertThat(result.getExternalRouteId()).isEqualTo("121");
        }

        @Test
        @DisplayName("Невідомий rtsId — routeName = null")
        void toPositionDto_unknownRtsId_routeNameNull() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(99999L, "5453", 48.27547, 25.92966, 30, 45);

            VehiclePositionDto result = invokeToPositionDto(dto);

            assertThat(result.getRouteName()).isNull();
            assertThat(result.getExternalRouteId()).isEqualTo("99999");
        }

        @Test
        @DisplayName("Координати правильно копіюються (lat, lon)")
        void toPositionDto_coordinates_copiedCorrectly() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(121L, "5453", 48.27547, 25.92966, 30, 45);

            VehiclePositionDto result = invokeToPositionDto(dto);

            assertThat(result.getLat()).isEqualTo(48.27547);
            assertThat(result.getLng()).isEqualTo(25.92966);
        }

        @Test
        @DisplayName("speed і bearing правильно конвертуються в Float")
        void toPositionDto_speedAndBearing_convertedToFloat() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(121L, "5453", 48.27547, 25.92966, 42, 180);

            VehiclePositionDto result = invokeToPositionDto(dto);

            assertThat(result.getSpeed()).isEqualTo(42.0f);
            assertThat(result.getBearing()).isEqualTo(180.0f);
        }

        @Test
        @DisplayName("null speed і bearing → 0f")
        void toPositionDto_nullSpeedAndBearing_defaultZero() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(121L, "5453", 48.27547, 25.92966, null, null);

            VehiclePositionDto result = invokeToPositionDto(dto);

            assertThat(result.getSpeed()).isEqualTo(0f);
            assertThat(result.getBearing()).isEqualTo(0f);
        }

        @Test
        @DisplayName("externalId = id транспортного засобу")
        void toPositionDto_externalId_mapsCorrectly() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(121L, "5453", 48.27547, 25.92966, 30, 45);
            dto.setId(12345L);

            VehiclePositionDto result = invokeToPositionDto(dto);

            assertThat(result.getExternalId()).isEqualTo("12345");
        }

        @Test
        @DisplayName("busNumber = transportNumber")
        void toPositionDto_busNumber_mapsCorrectly() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(121L, "5453", 48.27547, 25.92966, 30, 45);

            VehiclePositionDto result = invokeToPositionDto(dto);

            assertThat(result.getBusNumber()).isEqualTo("5453");
        }

        @Test
        @DisplayName("Тип завжди BUS")
        void toPositionDto_type_alwaysBus() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(121L, "5453", 48.27547, 25.92966, 30, 45);

            VehiclePositionDto result = invokeToPositionDto(dto);

            assertThat(result.getType()).isEqualTo(TransportType.BUS);
        }

        @Test
        @DisplayName("Source = transportcv")
        void toPositionDto_source_transportcv() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(121L, "5453", 48.27547, 25.92966, 30, 45);

            VehiclePositionDto result = invokeToPositionDto(dto);

            assertThat(result.getSource()).isEqualTo(DataSource.transportcv);
        }

        @Test
        @DisplayName("online завжди true")
        void toPositionDto_online_alwaysTrue() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(121L, "5453", 48.27547, 25.92966, 30, 45);

            VehiclePositionDto result = invokeToPositionDto(dto);

            assertThat(result.getOnline()).isTrue();
        }
    }


    @Nested
    @DisplayName("ROUTE_NAMES маппінг")
    class RouteMappingTests {

        @Test
        @DisplayName("Всі відомі маршрути правильно мапляться")
        void allKnownRoutes_mappedCorrectly() throws Exception {
            Long[] knownRouteIds = {121L, 823L, 1922L, 842L, 863L, 1942L, 1421L};
            String[] expectedNames = {"3", "21", "26", "33", "36", "37", "43"};

            for (int i = 0; i < knownRouteIds.length; i++) {
                TransportCvVehicleDto dto = createVehicleDto(knownRouteIds[i], "5453", 48.27547, 25.92966, 30, 45);
                VehiclePositionDto result = invokeToPositionDto(dto);
                assertThat(result.getRouteName()).isEqualTo(expectedNames[i]);
            }
        }

        @Test
        @DisplayName("Невідомий маршрут не має назви")
        void unknownRoute_noMapping() throws Exception {
            TransportCvVehicleDto dto = createVehicleDto(99999L, "5453", 48.27547, 25.92966, 30, 45);
            VehiclePositionDto result = invokeToPositionDto(dto);
            assertThat(result.getRouteName()).isNull();
        }
    }

    // ── Допоміжні методи ──────────────────────────────────────────────────────

    /**
     * Створює тестовий TransportCvVehicleDto
     */
    private TransportCvVehicleDto createVehicleDto(Long rtsId, String transportNumber,
                                                   Double lat, Double lon,
                                                   Integer speed, Integer angle) {
        TransportCvVehicleDto dto = new TransportCvVehicleDto();
        dto.setId(12345L);
        dto.setRtsId(rtsId);
        dto.setTransportNumber(transportNumber);
        dto.setLat(lat);
        dto.setLon(lon);
        dto.setSpeed(speed);
        dto.setAngle(angle);
        dto.setDatetime("2026-05-13T19:48:50Z");
        dto.setStatusName("move");
        return dto;
    }

    /**
     * Створює TransportCvResponseDto з масивом транспортів
     */
    private TransportCvResponseDto createResponse(TransportCvVehicleDto... vehicles) {
        TransportCvResponseDto response = new TransportCvResponseDto();
        response.setTransports(List.of(vehicles));
        return response;
    }

    /**
     * Викликає приватний метод toPositionDto через reflection
     */
    private VehiclePositionDto invokeToPositionDto(TransportCvVehicleDto dto) throws Exception {
        Method method = TransportCvCollector.class.getDeclaredMethod("toPositionDto", TransportCvVehicleDto.class);
        method.setAccessible(true);
        return (VehiclePositionDto) method.invoke(collector, dto);
    }

    /**
     * Викликає приватний метод для отримання позицій з response
     */
    @SuppressWarnings("unchecked")
    private List<VehiclePositionDto> invokeExtractPositions(TransportCvResponseDto response) throws Exception {
        Method method = TransportCvCollector.class.getDeclaredMethod("extractPositions", TransportCvResponseDto.class);
        method.setAccessible(true);
        return (List<VehiclePositionDto>) method.invoke(collector, response);
    }
}