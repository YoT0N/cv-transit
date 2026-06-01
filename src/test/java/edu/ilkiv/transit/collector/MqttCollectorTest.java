package edu.ilkiv.transit.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.service.VehicleAggregationService;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Тести для MqttCollector.
 * Тестуємо messageArrived та маппінг через reflection,
 * уникаючи реального MQTT підключення (@PostConstruct).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MqttCollector — unit tests")
class MqttCollectorTest {

    @Mock
    VehicleAggregationService aggregationService;

    ObjectMapper objectMapper = new ObjectMapper();

    MqttCollector collector;

    @BeforeEach
    void setUp() {
        collector = new MqttCollector(aggregationService, objectMapper);
    }

    // ── messageArrived — повний payload ──────────────────────────────────────

    @Nested
    @DisplayName("messageArrived")
    class MessageArrivedTests {

        @Test
        @DisplayName("Валідний payload — передається в aggregationService")
        void messageArrived_validPayload_aggregated() throws Exception {
            String payload = """
                    {
                      "id": 93760,
                      "tm": 1747000000,
                      "msg": {
                        "pos": { "x": 25.93, "y": 48.27, "s": 14, "c": 160 },
                        "r": 19055,
                        "t": 1747000000
                      }
                    }
                    """;

            MqttMessage message = new MqttMessage(payload.getBytes());
            collector.messageArrived("nimbus/locator/token/93760", message);

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());

            VehiclePositionDto dto = captor.getValue().get(0);
            assertThat(dto.getExternalId()).isEqualTo("93760");
            assertThat(dto.getSource()).isEqualTo(DataSource.nimbus);
            assertThat(dto.getLat()).isEqualTo(48.27);
            assertThat(dto.getLng()).isEqualTo(25.93);
            assertThat(dto.getSpeed()).isEqualTo(14f);
            assertThat(dto.getBearing()).isEqualTo(160f);
            assertThat(dto.getOnline()).isTrue();
        }

        @Test
        @DisplayName("Payload без pos — не передається в aggregationService")
        void messageArrived_noPos_notAggregated() throws Exception {
            String payload = """
                    {
                      "id": 12345,
                      "msg": { "r": 100 }
                    }
                    """;

            MqttMessage message = new MqttMessage(payload.getBytes());
            collector.messageArrived("nimbus/locator/token/12345", message);

            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Payload з null msg — не передається")
        void messageArrived_nullMsg_notAggregated() throws Exception {
            String payload = """
                    {
                      "id": 12345
                    }
                    """;

            MqttMessage message = new MqttMessage(payload.getBytes());
            collector.messageArrived("nimbus/locator/token/12345", message);

            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Невалідний JSON — не кидає exception")
        void messageArrived_invalidJson_noException() {
            MqttMessage message = new MqttMessage("not-json{{{".getBytes());

            assertThatCode(() ->
                    collector.messageArrived("topic", message)
            ).doesNotThrowAnyException();

            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Координати поза межами Чернівців — не передаються")
        void messageArrived_coordsOutOfBounds_notAggregated() throws Exception {
            String payload = """
                    {
                      "id": 99999,
                      "msg": {
                        "pos": { "x": 30.52, "y": 50.45, "s": 0, "c": 0 }
                      }
                    }
                    """;

            MqttMessage message = new MqttMessage(payload.getBytes());
            collector.messageArrived("topic", message);

            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("null координати у pos — не передаються")
        void messageArrived_nullCoords_notAggregated() throws Exception {
            String payload = """
                    {
                      "id": 55555,
                      "msg": {
                        "pos": { "s": 10, "c": 90 }
                      }
                    }
                    """;

            MqttMessage message = new MqttMessage(payload.getBytes());
            collector.messageArrived("topic", message);

            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Payload без routeId — externalRouteId = null")
        void messageArrived_noRouteId_externalRouteIdNull() throws Exception {
            String payload = """
                    {
                      "id": 77777,
                      "msg": {
                        "pos": { "x": 25.93, "y": 48.27, "s": 5, "c": 90 }
                      }
                    }
                    """;

            MqttMessage message = new MqttMessage(payload.getBytes());
            collector.messageArrived("topic", message);

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());

            assertThat(captor.getValue().get(0).getExternalRouteId()).isNull();
        }

        @Test
        @DisplayName("null speed і course — default 0f")
        void messageArrived_nullSpeedAndCourse_defaultZero() throws Exception {
            String payload = """
                    {
                      "id": 11111,
                      "msg": {
                        "pos": { "x": 25.93, "y": 48.27 }
                      }
                    }
                    """;

            MqttMessage message = new MqttMessage(payload.getBytes());
            collector.messageArrived("topic", message);

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());

            VehiclePositionDto dto = captor.getValue().get(0);
            assertThat(dto.getSpeed()).isEqualTo(0f);
            assertThat(dto.getBearing()).isEqualTo(0f);
        }
    }

    // ── connectionLost ────────────────────────────────────────────────────────

    @Test
    @DisplayName("connectionLost — не кидає exception")
    void connectionLost_noException() {
        assertThatCode(() ->
                collector.connectionLost(new RuntimeException("test disconnect"))
        ).doesNotThrowAnyException();
    }

    // ── deliveryComplete ──────────────────────────────────────────────────────

    @Test
    @DisplayName("deliveryComplete — не кидає exception")
    void deliveryComplete_noException() {
        assertThatCode(() ->
                collector.deliveryComplete(null)
        ).doesNotThrowAnyException();
    }
}