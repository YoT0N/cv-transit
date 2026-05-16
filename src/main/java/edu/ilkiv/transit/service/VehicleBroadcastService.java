package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.VehiclePositionEvent;
import edu.ilkiv.transit.model.Vehicle;
import edu.ilkiv.transit.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Пушить оновлення позицій транспорту підписаним WebSocket клієнтам.
 *
 * ВАЖЛИВО: broadcast() отримує лише щойно оновлені vehicles (з одного джерела),
 * але надсилає клієнту ВСІ онлайн-vehicles з БД — щоб маркери від інших
 * джерел (TransGps, Nimbus) не зникали коли прийшов пакет від TransportCv.
 *
 * Топік: /topic/vehicles — всі онлайн транспортні засоби.
 * Топік: /topic/routes/{routeName} — транспорт конкретного маршруту.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleBroadcastService {

    private static final String TOPIC_ALL   = "/topic/vehicles";
    private static final String TOPIC_ROUTE = "/topic/routes/";

    private final SimpMessagingTemplate messagingTemplate;
    private final VehicleRepository vehicleRepository;

    /**
     * @param updatedVehicles — vehicles щойно оновлені колектором (використовується тільки для логу)
     */
    public void broadcast(List<Vehicle> updatedVehicles) {
        if (updatedVehicles.isEmpty()) return;

        // Надсилаємо ВСІ онлайн-vehicles, а не тільки щойно оновлені.
        // Інакше пакет від TransportCv (3 авто) змушує фронтенд
        // видалити маркери TransGps (18 авто) і навпаки.
        List<Vehicle> allOnline = vehicleRepository.findByIsOnlineTrue();

        List<VehiclePositionEvent> events = allOnline.stream()
                .map(this::toEvent)
                .toList();

        messagingTemplate.convertAndSend(TOPIC_ALL, events);
        log.debug("Broadcast {} total online vehicles ({} just updated) to {}",
                events.size(), updatedVehicles.size(), TOPIC_ALL);

        // Пушимо по маршруту (для клієнтів що стежать за конкретним маршрутом)
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