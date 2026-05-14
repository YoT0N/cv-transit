package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.VehiclePositionEvent;
import edu.ilkiv.transit.model.Vehicle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Пушить оновлення позицій транспорту підписаним WebSocket клієнтам.
 * Топік: /topic/vehicles — всі онлайн транспортні засоби.
 * Топік: /topic/routes/{routeName} — транспорт конкретного маршруту.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleBroadcastService {

    private static final String TOPIC_ALL    = "/topic/vehicles";
    private static final String TOPIC_ROUTE  = "/topic/routes/";

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcast(List<Vehicle> vehicles) {
        if (vehicles.isEmpty()) return;

        List<VehiclePositionEvent> events = vehicles.stream()
                .map(this::toEvent)
                .toList();

        // Пушимо всі позиції разом (один батч замість N повідомлень)
        messagingTemplate.convertAndSend(TOPIC_ALL, events);
        log.debug("Broadcast {} vehicles to {}", events.size(), TOPIC_ALL);

        // Також пушимо по маршруту (для клієнтів, що стежать за конкретним маршрутом)
        events.stream()
                .filter(e -> e.getRouteName() != null)
                .collect(java.util.stream.Collectors.groupingBy(VehiclePositionEvent::getRouteName))
                .forEach((routeName, routeEvents) ->
                        messagingTemplate.convertAndSend(TOPIC_ROUTE + routeName, routeEvents)
                );
    }

    private VehiclePositionEvent toEvent(Vehicle v) {
        return VehiclePositionEvent.builder()
                .vehicleId(v.getId())
                .routeName(v.getRoute() != null ? v.getRoute().getName() : null)
                .type(v.getRoute() != null ? v.getRoute().getType() : null)
                .lat(v.getLat())
                .lng(v.getLng())
                .speed(v.getSpeed())
                .bearing(v.getBearing())
                .busNumber(v.getBusNumber())
                .color(v.getRoute() != null ? v.getRoute().getColor() : null)
                .online(v.getIsOnline())
                .build();
    }
}