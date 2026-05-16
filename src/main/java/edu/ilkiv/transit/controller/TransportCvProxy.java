package edu.ilkiv.transit.controller;

import edu.ilkiv.transit.util.TransportCvSignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Локальний проксі до transport.cv.ua/api/positions.
 *
 * GET  /api/proxy/transport-cv/positions        — повертає JSON з транспортом (всі маршрути)
 * POST /api/proxy/transport-cv/positions        — тіло запиту пробрасовується ({"routeIds":[1,2,...]})
 *
 * Призначення:
 *   Сервер transport.cv.ua перевіряє Origin/Referer і очікує
 *   браузерні заголовки. Цей проксі додає правильний Referer,
 *   Origin, Accept, і обчислює Sign/Reqdate — запит виглядає
 *   як такий що прийшов безпосередньо із сайту transport.cv.ua.
 *
 * Додатково корисний для збору даних без запуску повного
 * Spring-додатку — можна викликати curl або будь-яким HTTP-клієнтом.
 */
@Slf4j
@RestController
@RequestMapping("/api/proxy/transport-cv")
@RequiredArgsConstructor
public class TransportCvProxy {

    private static final String TARGET_URL = "https://transport.cv.ua/api/positions";

    // Браузерні заголовки що сервер очікує побачити
    private static final String REFERER    = "https://transport.cv.ua/";
    private static final String ORIGIN     = "https://transport.cv.ua";
    private static final String ACCEPT     = "application/json, text/plain, */*";
    private static final String ACCEPT_LANG = "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7";

    private final WebClient webClient;

    /**
     * GET /api/proxy/transport-cv/positions
     * Повертає всі онлайн транспортні засоби (routeIds порожній).
     */
    @GetMapping("/positions")
    public ResponseEntity<String> getAllPositions() {
        return fetchPositions("{\"routeIds\":[]}");
    }

    /**
     * POST /api/proxy/transport-cv/positions
     * Тіло запиту: {"routeIds":[19055, 20208, ...]}
     * Пробрасовує фільтрований запит до сервера.
     */
    @PostMapping("/positions")
    public ResponseEntity<String> getPositionsByRoutes(@RequestBody String body) {
        return fetchPositions(body);
    }

    // ── Внутрішня логіка ──────────────────────────────────────────────────────

    private ResponseEntity<String> fetchPositions(String requestBody) {
        String reqDate = TransportCvSignature.buildReqDate();
        String sign    = TransportCvSignature.buildSign(reqDate, TransportCvSignature.USER_AGENT);

        log.debug("TransportCvProxy -> Reqdate: [{}]", reqDate);
        log.debug("TransportCvProxy -> Sign:    [{}]", sign);

        try {
            String responseBody = webClient
                    .mutate()
                    .filter(forceUserAgent(TransportCvSignature.USER_AGENT))
                    .build()
                    .post()
                    .uri(TARGET_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Reqdate",                reqDate)
                    .header("Sign",                   sign)
                    .header(HttpHeaders.USER_AGENT,   TransportCvSignature.USER_AGENT)
                    .header(HttpHeaders.REFERER,      REFERER)
                    .header(HttpHeaders.ORIGIN,       ORIGIN)
                    .header(HttpHeaders.ACCEPT,       ACCEPT)
                    .header("Accept-Language",        ACCEPT_LANG)
                    // Без цього сервер може відхилити як не-браузерний запит
                    .header("Sec-Fetch-Dest",  "empty")
                    .header("Sec-Fetch-Mode",  "cors")
                    .header("Sec-Fetch-Site",  "same-origin")
                    .bodyValue(requestBody)
                    .exchangeToMono(response -> {
                        log.debug("TransportCvProxy response status: {}", response.statusCode());
                        if (response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(String.class);
                        } else {
                            return response.bodyToMono(String.class)
                                    .doOnNext(body -> log.warn(
                                            "TransportCvProxy error {}: {}", response.statusCode(), body));
                        }
                    })
                    .block();

            if (responseBody == null) {
                return ResponseEntity.internalServerError().body("{\"error\":\"empty response\"}");
            }

            log.debug("TransportCvProxy: got {} chars", responseBody.length());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(responseBody);

        } catch (Exception e) {
            log.error("TransportCvProxy: exception — {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * ExchangeFilterFunction що гарантовано перезаписує User-Agent
     * перед відправкою — Reactor Netty не зможе підставити свій.
     */
    private static ExchangeFilterFunction forceUserAgent(String userAgent) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            ClientRequest modified = ClientRequest.from(request)
                    .headers(h -> h.set(HttpHeaders.USER_AGENT, userAgent))
                    .build();
            return Mono.just(modified);
        });
    }
}