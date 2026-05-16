package edu.ilkiv.transit.collector;

import edu.ilkiv.transit.dto.TransGpsVehicleDto;
import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.service.VehicleAggregationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransGpsCollector — unit tests")
class TransGpsCollectorTest {

    // ── Мокаємо WebClient fluent chain ───────────────────────────────────────
    @Mock WebClient webClient;
    @Mock WebClient.RequestHeadersUriSpec  requestHeadersUriSpec;
    @Mock WebClient.RequestHeadersSpec     requestHeadersSpec;
    @Mock WebClient.ResponseSpec           responseSpec;

    @Mock VehicleAggregationService aggregationService;

    @InjectMocks
    TransGpsCollector collector;

    // ── Хелпер: створити TransGpsVehicleDto ──────────────────────────────────

    private TransGpsVehicleDto buildDto(String imei, String routeName,
                                        Integer routeId, boolean online, boolean inDepo) {
        TransGpsVehicleDto dto = new TransGpsVehicleDto();
        dto.setImei(imei);
        dto.setLat(48.29);
        dto.setLng(25.93);
        dto.setSpeed("020.5");
        dto.setOrientation("045.00");
        dto.setRouteName(routeName);
        dto.setRouteId(routeId);
        dto.setRouteColour("#FF0000");
        dto.setBusNumber("4811");
        dto.setOnline(online);
        dto.setInDepo(inDepo);
        return dto;
    }

    private void mockWebClient(Map<String, TransGpsVehicleDto> response) {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(response));
    }

    // ── Тест 1: нормальна відповідь ──────────────────────────────────────────

    @Test
    @DisplayName("Нормальна відповідь — positions передаються до aggregationService")
    void collect_normalResponse_passesPositionsToAggregation() {
        // given
        Map<String, TransGpsVehicleDto> response = Map.of(
                "imei-1", buildDto("imei-1", "10", 10, true, false),
                "imei-2", buildDto("imei-2", "20", 20, true, false)
        );
        mockWebClient(response);

        // when
        collector.collect();

        // then
        ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(aggregationService).processPositions(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    // ── Тест 2: маппінг полів ────────────────────────────────────────────────

    @Test
    @DisplayName("Маппінг полів — speed, bearing, source встановлені правильно")
    void collect_fieldMapping_correct() {
        // given
        TransGpsVehicleDto dto = buildDto("imei-map", "9A", 9, true, false);
        mockWebClient(Map.of("imei-map", dto));

        // when
        collector.collect();

        // then
        ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(aggregationService).processPositions(captor.capture());

        VehiclePositionDto position = captor.getValue().get(0);
        assertThat(position.getExternalId()).isEqualTo("imei-map");
        assertThat(position.getSource()).isEqualTo(DataSource.transgps);
        assertThat(position.getRouteName()).isEqualTo("9A");
        assertThat(position.getSpeed()).isEqualTo(20.5f);
        assertThat(position.getBearing()).isEqualTo(45.0f);
        assertThat(position.getLat()).isEqualTo(48.29);
        assertThat(position.getLng()).isEqualTo(25.93);
        assertThat(position.getBusNumber()).isEqualTo("4811");
        assertThat(position.getColor()).isEqualTo("#FF0000");
    }

    // ── Тест 3: фільтрація — null координати ─────────────────────────────────

    @Test
    @DisplayName("Vehicle з null координатами — фільтрується, не передається")
    void collect_nullCoordinates_filtered() {
        // given
        TransGpsVehicleDto noCoords = buildDto("imei-null", "10", 10, true, false);
        noCoords.setLat(null);
        noCoords.setLng(null);

        TransGpsVehicleDto valid = buildDto("imei-valid", "10", 10, true, false);

        mockWebClient(Map.of("imei-null", noCoords, "imei-valid", valid));

        // when
        collector.collect();

        // then — тільки valid передано
        ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(aggregationService).processPositions(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getExternalId()).isEqualTo("imei-valid");
    }

    // ── Тест 4: фільтрація — в депо і офлайн ─────────────────────────────────

    @Test
    @DisplayName("Vehicle в депо і офлайн — фільтрується")
    void collect_inDepoAndOffline_filtered() {
        // given
        TransGpsVehicleDto inDepo = buildDto("imei-depo", "10", 10, false, true);
        TransGpsVehicleDto online  = buildDto("imei-online", "10", 10, true, false);

        mockWebClient(Map.of("imei-depo", inDepo, "imei-online", online));

        // when
        collector.collect();

        // then
        ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(aggregationService).processPositions(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getExternalId()).isEqualTo("imei-online");
    }

    // ── Тест 5: порожня відповідь ────────────────────────────────────────────

    @Test
    @DisplayName("Порожня відповідь від сервера — aggregationService не викликається")
    void collect_emptyResponse_aggregationNotCalled() {
        // given
        mockWebClient(Map.of());

        // when
        collector.collect();

        // then
        verifyNoInteractions(aggregationService);
    }

    // ── Тест 6: помилка мережі ───────────────────────────────────────────────

    @Test
    @DisplayName("Мережева помилка — не кидає exception, aggregationService не викликається")
    void collect_networkError_noExceptionThrown() {
        // given
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));

        // when — не має кидати exception
        collector.collect();

        // then
        verifyNoInteractions(aggregationService);
    }
}