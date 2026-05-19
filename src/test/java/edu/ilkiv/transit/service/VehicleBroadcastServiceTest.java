package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.VehiclePositionEvent;
import edu.ilkiv.transit.model.*;
import edu.ilkiv.transit.repository.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VehicleBroadcastService — unit tests")
class VehicleBroadcastServiceTest {

    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock VehicleRepository vehicleRepository;

    @InjectMocks VehicleBroadcastService broadcastService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Vehicle buildVehicle(Long id, String routeName, TransportType type) {
        Route route = new Route();
        route.setName(routeName);
        route.setType(type);
        route.setColor("#1E88E5");

        Vehicle v = new Vehicle();
        v.setId(id);
        v.setRoute(route);
        v.setLat(48.29);
        v.setLng(25.93);
        v.setSpeed(40f);
        v.setBearing(180f);
        v.setBusNumber("BUS-" + id);
        v.setIsOnline(true);
        return v;
    }

    private Vehicle buildVehicleNoRoute(Long id) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setLat(48.0);
        v.setLng(25.0);
        v.setIsOnline(true);
        return v;
    }

    // ── broadcast — порожній список ───────────────────────────────────────────

    @Test
    @DisplayName("broadcast — порожній список → нічого не надсилається")
    void broadcast_emptyList_noInteraction() {
        broadcastService.broadcast(Collections.emptyList());

        verifyNoInteractions(messagingTemplate, vehicleRepository);
    }

    // ── broadcast — загальний топік ───────────────────────────────────────────

    @Test
    @DisplayName("broadcast — надсилає всі онлайн-vehicles на /topic/vehicles")
    void broadcast_sendsAllOnlineToMainTopic() {
        Vehicle v1 = buildVehicle(1L, "10", TransportType.BUS);
        Vehicle v2 = buildVehicle(2L, "3", TransportType.TROLL);

        when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(v1, v2));

        broadcastService.broadcast(List.of(v1));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/vehicles"), payloadCaptor.capture());

        @SuppressWarnings("unchecked")
        List<VehiclePositionEvent> events = (List<VehiclePositionEvent>) payloadCaptor.getValue();
        assertThat(events).hasSize(2);
    }

    // ── broadcast — маппінг полів ─────────────────────────────────────────────

    @Test
    @DisplayName("broadcast — поля VehiclePositionEvent заповнені коректно")
    void broadcast_eventFieldMapping_correct() {
        Vehicle v = buildVehicle(42L, "9A", TransportType.BUS);
        when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(v));

        broadcastService.broadcast(List.of(v));

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/vehicles"), cap.capture());

        @SuppressWarnings("unchecked")
        List<VehiclePositionEvent> result = (List<VehiclePositionEvent>) cap.getValue();

        VehiclePositionEvent event = result.get(0);
        assertThat(event.getVehicleId()).isEqualTo(42L);
        assertThat(event.getRouteName()).isEqualTo("9A");
        assertThat(event.getType()).isEqualTo(TransportType.BUS);
        assertThat(event.getLat()).isEqualTo(48.29);
        assertThat(event.getLng()).isEqualTo(25.93);
        assertThat(event.getSpeed()).isEqualTo(40f);
        assertThat(event.getBearing()).isEqualTo(180f);
        assertThat(event.getBusNumber()).isEqualTo("BUS-42");
        assertThat(event.getColor()).isEqualTo("#1E88E5");
        assertThat(event.getOnline()).isTrue();
    }

    // ── broadcast — топіки маршрутів ─────────────────────────────────────────

/*    @Test
    @DisplayName("broadcast — надсилає в /topic/routes/{routeName} для кожного маршруту")
    void broadcast_sendsToRouteTopics() {
        Vehicle v1 = buildVehicle(1L, "10", TransportType.BUS);
        Vehicle v2 = buildVehicle(2L, "3", TransportType.BUS);

        when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(v1, v2));

        broadcastService.broadcast(List.of(v1));

        verify(messagingTemplate).convertAndSend(eq("/topic/routes/10"), any());
        verify(messagingTemplate).convertAndSend(eq("/topic/routes/3"), any());
    }*/

/*    @Test
    @DisplayName("broadcast — vehicle без маршруту не потрапляє в топік маршруту")
    void broadcast_vehicleWithoutRoute_notInRouteTopic() {
        Vehicle noRoute = buildVehicleNoRoute(5L);
        Vehicle withRoute = buildVehicle(6L, "7", TransportType.BUS);

        when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(noRoute, withRoute));

        broadcastService.broadcast(List.of(noRoute));

        // /topic/routes/7 має бути, але не /topic/routes/null
        verify(messagingTemplate).convertAndSend(eq("/topic/routes/7"), any());
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/routes/null"), any());
    }*/

    // ── broadcast — кількість онлайн-vehicles ─────────────────────────────────

    @Test
    @DisplayName("broadcast — надсилає ВСІ онлайн-vehicles, а не тільки оновлені")
    void broadcast_sendsAllOnline_notOnlyUpdated() {
        // 5 онлайн в БД, але оновили тільки 1
        List<Vehicle> allOnline = List.of(
                buildVehicle(1L, "10", TransportType.BUS),
                buildVehicle(2L, "10", TransportType.BUS),
                buildVehicle(3L, "10", TransportType.BUS),
                buildVehicle(4L, "10", TransportType.BUS),
                buildVehicle(5L, "10", TransportType.BUS)
        );
        when(vehicleRepository.findByIsOnlineTrue()).thenReturn(allOnline);

        broadcastService.broadcast(List.of(allOnline.get(0)));

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/vehicles"), cap.capture());
        @SuppressWarnings("unchecked")
        List<VehiclePositionEvent> result = (List<VehiclePositionEvent>) cap.getValue();
        assertThat(result).hasSize(5);
    }
}