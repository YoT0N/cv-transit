package edu.ilkiv.transit.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@RestController
@RequestMapping("/api/proxy")
@RequiredArgsConstructor
public class EasyWayProxy {

    private final WebClient webClient;

    @GetMapping("/stops")
    public ResponseEntity<String> getStops() {
        try {
            String response = webClient.get()
                    .uri("https://www.eway.in.ua/ajax/ua/chernivtsi/stops")
                    .header("Referer", "https://www.eway.in.ua/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(response);
        } catch (Exception e) {
            log.error("EasyWayProxy stops failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}