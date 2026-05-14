package edu.ilkiv.transit.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Вмикає підтримку @Scheduled анотацій для колекторів.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}