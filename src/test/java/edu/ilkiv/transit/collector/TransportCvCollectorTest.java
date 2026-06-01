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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransportCvCollector — unit tests")
class TransportCvCollectorTest {

    @Mock VehicleAggregationService aggregationService;
    @Mock WebClient webClient;
    @Mock WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock WebClient.RequestBodySpec requestBodySpec;
    @Mock WebClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock WebClient.ResponseSpec responseSpec;
    @Mock WebClient.Builder webClientBuilder;

    TransportCvCollector collector;

    @BeforeEach
    void setUp() {
        collector = new TransportCvCollector(webClient, aggregationService);
    }

    // ── Хелпери ───────────────────────────────────────────────────────────────

    private TransportCvVehicleDto createDto(Long rtsId) {
        TransportCvVehicleDto dto = new TransportCvVehicleDto();
        dto.setId(1L);
        dto.setRtsId(rtsId);
        dto.setTransportNumber("5453");
        dto.setLat(48.29);
        dto.setLon(25.93);
        dto.setSpeed(30);
        dto.setAngle(90);
        return dto;
    }

    /**
     * Налаштовує WebClient fluent chain для collect().
     * collect() викликає webClient.mutate().filter(...).build().post()...exchangeToMono(...)
     * Тому мокаємо mutate() → повертає builder → build() → повертає новий mock WebClient.
     */
    @SuppressWarnings("unchecked")
    private void mockCollectChain(Mono<?> exchangeMono) {
        WebClient builtClient = mock(WebClient.class);

        when(webClient.mutate()).thenReturn(webClientBuilder);
        when(webClientBuilder.filter(any())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(builtClient);
        when(builtClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestBodySpec);
        when(requestBodySpec.exchangeToMono(any())).thenReturn((Mono) exchangeMono);
    }

    private TransportCvResponseDto responseWith(List<TransportCvVehicleDto> transports) {
        TransportCvResponseDto resp = new TransportCvResponseDto();
        resp.setTransports(transports);
        return resp;
    }

    // ── collect() — успішні сценарії ──────────────────────────────────────────

    @Nested
    @DisplayName("collect() — успішні сценарії")
    class CollectSuccessTests {

        @Test
        @DisplayName("Нормальна відповідь — positions передаються до aggregationService")
        void collect_normalResponse_aggregated() {
            TransportCvVehicleDto dto = createDto(121L);
            mockCollectChain(Mono.just(responseWith(List.of(dto))));

            collector.collect();

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).getSource()).isEqualTo(DataSource.transportcv);
        }

        @Test
        @DisplayName("Кілька vehicles — всі передаються")
        void collect_multipleVehicles_allAggregated() {
            mockCollectChain(Mono.just(responseWith(
                    List.of(createDto(121L), createDto(823L), createDto(863L)))));

            collector.collect();

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());
            assertThat(captor.getValue()).hasSize(3);
        }

        @Test
        @DisplayName("Vehicle з null lat — фільтрується")
        void collect_nullLat_filtered() {
            TransportCvVehicleDto noLat = createDto(121L);
            noLat.setLat(null);
            TransportCvVehicleDto valid = createDto(863L);

            mockCollectChain(Mono.just(responseWith(List.of(noLat, valid))));

            collector.collect();

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("Vehicle з null lon — фільтрується")
        void collect_nullLon_filtered() {
            TransportCvVehicleDto noLon = createDto(121L);
            noLon.setLon(null);
            TransportCvVehicleDto valid = createDto(863L);

            mockCollectChain(Mono.just(responseWith(List.of(noLon, valid))));

            collector.collect();

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("Невідомий rtsId — передається з routeName=null (логується)")
        void collect_unknownRtsId_passedWithNullRouteName() {
            mockCollectChain(Mono.just(responseWith(List.of(createDto(99999L)))));

            collector.collect();

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());
            assertThat(captor.getValue().get(0).getRouteName()).isNull();
        }
    }

    // ── collect() — порожні/null відповіді ────────────────────────────────────

    @Nested
    @DisplayName("collect() — порожні та null відповіді")
    class CollectEmptyResponseTests {

        @Test
        @DisplayName("Mono.empty() (null після block()) — aggregationService не викликається")
        void collect_nullResponse_notAggregated() {
            mockCollectChain(Mono.empty());

            collector.collect();

            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Відповідь з null transports — aggregationService не викликається")
        void collect_nullTransports_notAggregated() {
            mockCollectChain(Mono.just(responseWith(null)));

            collector.collect();

            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Відповідь з порожнім списком transports — aggregationService не викликається")
        void collect_emptyTransports_notAggregated() {
            mockCollectChain(Mono.just(responseWith(Collections.emptyList())));

            collector.collect();

            verifyNoInteractions(aggregationService);
        }
    }

    // ── collect() — помилки ───────────────────────────────────────────────────

    @Nested
    @DisplayName("collect() — обробка помилок")
    class CollectErrorTests {

        @Test
        @DisplayName("RuntimeException під час block() — не кидає exception назовні")
        void collect_runtimeException_noExceptionPropagated() {
            mockCollectChain(Mono.error(new RuntimeException("Connection refused")));

            assertThatCode(() -> collector.collect()).doesNotThrowAnyException();
            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("WebClient chain кидає exception — не кидає exception назовні")
        void collect_webClientException_noExceptionPropagated() {
            when(webClient.mutate()).thenThrow(new RuntimeException("WebClient failed"));

            assertThatCode(() -> collector.collect()).doesNotThrowAnyException();
            verifyNoInteractions(aggregationService);
        }
    }

    // ── ROUTE_NAMES маппінг ───────────────────────────────────────────────────

    @Nested
    @DisplayName("ROUTE_NAMES — маппінг rtsId на назву маршруту")
    class RouteNamesMappingTests {

        @Test
        @DisplayName("rtsId=121 → маршрут '3'")
        void rtsId121_route3() throws Exception {
            assertThat(invokeToPositionDto(createDto(121L)).getRouteName()).isEqualTo("3");
        }

        @Test
        @DisplayName("rtsId=823 → маршрут '21'")
        void rtsId823_route21() throws Exception {
            assertThat(invokeToPositionDto(createDto(823L)).getRouteName()).isEqualTo("21");
        }

        @Test
        @DisplayName("rtsId=1922 → маршрут '26'")
        void rtsId1922_route26() throws Exception {
            assertThat(invokeToPositionDto(createDto(1922L)).getRouteName()).isEqualTo("26");
        }

        @Test
        @DisplayName("rtsId=842 → маршрут '33'")
        void rtsId842_route33() throws Exception {
            assertThat(invokeToPositionDto(createDto(842L)).getRouteName()).isEqualTo("33");
        }

        @Test
        @DisplayName("rtsId=863 → маршрут '36'")
        void rtsId863_route36() throws Exception {
            assertThat(invokeToPositionDto(createDto(863L)).getRouteName()).isEqualTo("36");
        }

        @Test
        @DisplayName("rtsId=1942 → маршрут '37'")
        void rtsId1942_route37() throws Exception {
            assertThat(invokeToPositionDto(createDto(1942L)).getRouteName()).isEqualTo("37");
        }

        @Test
        @DisplayName("rtsId=1421 → маршрут '43'")
        void rtsId1421_route43() throws Exception {
            assertThat(invokeToPositionDto(createDto(1421L)).getRouteName()).isEqualTo("43");
        }

        @Test
        @DisplayName("rtsId=2042 → маршрут '11'")
        void rtsId2042_route11() throws Exception {
            assertThat(invokeToPositionDto(createDto(2042L)).getRouteName()).isEqualTo("11");
        }

        @Test
        @DisplayName("rtsId=402 → маршрут '2'")
        void rtsId402_route2() throws Exception {
            assertThat(invokeToPositionDto(createDto(402L)).getRouteName()).isEqualTo("2");
        }

        @Test
        @DisplayName("Невідомий rtsId → routeName null")
        void unknownRtsId_routeNameNull() throws Exception {
            assertThat(invokeToPositionDto(createDto(99999L)).getRouteName()).isNull();
        }

        @Test
        @DisplayName("externalRouteId = rtsId як рядок")
        void externalRouteId_isRtsIdAsString() throws Exception {
            assertThat(invokeToPositionDto(createDto(863L)).getExternalRouteId())
                    .isEqualTo("863");
        }
    }

    // ── ROUTE_TYPES маппінг ───────────────────────────────────────────────────

    @Nested
    @DisplayName("ROUTE_TYPES — маппінг rtsId на тип транспорту")
    class RouteTypesMappingTests {

        @Test
        @DisplayName("rtsId=2042 (тролейбус 11) → тип TROLL")
        void rtsId2042_typeTroll() throws Exception {
            assertThat(invokeToPositionDto(createDto(2042L)).getType())
                    .isEqualTo(TransportType.TROLL);
        }

        @Test
        @DisplayName("rtsId=402 (тролейбус 2) → тип TROLL")
        void rtsId402_typeTroll() throws Exception {
            assertThat(invokeToPositionDto(createDto(402L)).getType())
                    .isEqualTo(TransportType.TROLL);
        }

        @Test
        @DisplayName("rtsId=121 → тип BUS")
        void rtsId121_typeBus() throws Exception {
            assertThat(invokeToPositionDto(createDto(121L)).getType())
                    .isEqualTo(TransportType.BUS);
        }

        @Test
        @DisplayName("rtsId=823 → тип BUS")
        void rtsId823_typeBus() throws Exception {
            assertThat(invokeToPositionDto(createDto(823L)).getType())
                    .isEqualTo(TransportType.BUS);
        }

        @Test
        @DisplayName("Невідомий rtsId → тип BUS за замовчуванням")
        void unknownRtsId_defaultBus() throws Exception {
            assertThat(invokeToPositionDto(createDto(88888L)).getType())
                    .isEqualTo(TransportType.BUS);
        }
    }

    // ── Поля DTO ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toPositionDto — маппінг полів DTO")
    class DtoFieldMappingTests {

        @Test
        @DisplayName("source завжди DataSource.transportcv")
        void source_alwaysTransportcv() throws Exception {
            assertThat(invokeToPositionDto(createDto(121L)).getSource())
                    .isEqualTo(DataSource.transportcv);
        }

        @Test
        @DisplayName("online завжди true")
        void online_alwaysTrue() throws Exception {
            assertThat(invokeToPositionDto(createDto(121L)).getOnline()).isTrue();
        }

        @Test
        @DisplayName("externalId = String.valueOf(dto.getId())")
        void externalId_mapsFromId() throws Exception {
            TransportCvVehicleDto dto = createDto(121L);
            dto.setId(12345L);
            assertThat(invokeToPositionDto(dto).getExternalId()).isEqualTo("12345");
        }

        @Test
        @DisplayName("busNumber = transportNumber")
        void busNumber_mapsFromTransportNumber() throws Exception {
            TransportCvVehicleDto dto = createDto(121L);
            dto.setTransportNumber("5453");
            assertThat(invokeToPositionDto(dto).getBusNumber()).isEqualTo("5453");
        }

        @Test
        @DisplayName("lat і lng (lon) копіюються правильно")
        void coordinates_copiedCorrectly() throws Exception {
            TransportCvVehicleDto dto = createDto(121L);
            dto.setLat(48.27547);
            dto.setLon(25.92966);
            VehiclePositionDto result = invokeToPositionDto(dto);
            assertThat(result.getLat()).isEqualTo(48.27547);
            assertThat(result.getLng()).isEqualTo(25.92966);
        }

        @Test
        @DisplayName("speed конвертується з Integer у Float")
        void speed_convertedToFloat() throws Exception {
            TransportCvVehicleDto dto = createDto(121L);
            dto.setSpeed(42);
            assertThat(invokeToPositionDto(dto).getSpeed()).isEqualTo(42.0f);
        }

        @Test
        @DisplayName("angle конвертується у bearing як Float")
        void bearing_convertedFromAngle() throws Exception {
            TransportCvVehicleDto dto = createDto(121L);
            dto.setAngle(180);
            assertThat(invokeToPositionDto(dto).getBearing()).isEqualTo(180.0f);
        }

        @Test
        @DisplayName("null speed → 0f")
        void nullSpeed_defaultZero() throws Exception {
            TransportCvVehicleDto dto = createDto(121L);
            dto.setSpeed(null);
            assertThat(invokeToPositionDto(dto).getSpeed()).isEqualTo(0f);
        }

        @Test
        @DisplayName("null angle → 0f")
        void nullAngle_defaultZero() throws Exception {
            TransportCvVehicleDto dto = createDto(121L);
            dto.setAngle(null);
            assertThat(invokeToPositionDto(dto).getBearing()).isEqualTo(0f);
        }

        @Test
        @DisplayName("speed=0 → 0f (не null)")
        void zeroSpeed_returnsZero() throws Exception {
            TransportCvVehicleDto dto = createDto(121L);
            dto.setSpeed(0);
            assertThat(invokeToPositionDto(dto).getSpeed()).isEqualTo(0f);
        }

        @Test
        @DisplayName("angle=360 → 360f")
        void maxAngle_correct() throws Exception {
            TransportCvVehicleDto dto = createDto(121L);
            dto.setAngle(360);
            assertThat(invokeToPositionDto(dto).getBearing()).isEqualTo(360f);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private VehiclePositionDto invokeToPositionDto(TransportCvVehicleDto dto) throws Exception {
        Method method = TransportCvCollector.class
                .getDeclaredMethod("toPositionDto", TransportCvVehicleDto.class);
        method.setAccessible(true);
        return (VehiclePositionDto) method.invoke(collector, dto);
    }
}