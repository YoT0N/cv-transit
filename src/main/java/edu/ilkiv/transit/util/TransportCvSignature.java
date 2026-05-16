package edu.ilkiv.transit.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Утиліта для формування підпису (Sign) до API transport.cv.ua.
 *
 * Алгоритм (розшифровано з обфускованого фронтенду transport.cv.ua):
 *   1. reqDate = поточний час у форматі RFC-1123 / UTC (toUTCString у JS)
 *      Приклад: "Tue, 13 May 2025 17:49:24 GMT"
 *   2. input   = SALT + reqDate + SALT + userAgent + SALT
 *   3. sign    = SHA-1(input).toLowerCase() — hex-рядок
 *
 * Заголовки запиту:
 *   Reqdate: <reqDate>
 *   Sign:    <sign>
 */
public final class TransportCvSignature {

    /** Статичний сіль — витягнуто безпосередньо з JS-коду сайту */
    static final String SALT = "Ao&1->3K4&PAS./";

    /**
     * User-Agent, що входить у підпис і надсилається в заголовку запиту.
     * Важливо: значення в заголовку User-Agent МУСИТЬ збігатися з тим,
     * що використовується при обчисленні SHA-1.
     *
     * Підтверджено порівнянням з реальним браузерним запитом Chrome 148 / Windows:
     *   reqDate="Sat, 16 May 2026 13:39:01 GMT"
     *   sign=0A67246DFC82C25CF442F1150828010599723170  ✓
     */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/148.0.0.0 Safari/537.36";

    /**
     * Формат повністю відповідає JS Date.prototype.toUTCString():
     *   "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
     * Локаль ENGLISH обов'язкова — інакше назви днів/місяців будуть українськими.
     */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    private TransportCvSignature() {}

    /**
     * Повертає поточний час у форматі, що очікує сервер (значення заголовка Reqdate).
     */
    public static String buildReqDate() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(FORMATTER);
    }

    /**
     * Обчислює підпис для запиту.
     *
     * @param reqDate   рядок з {@link #buildReqDate()} — той самий, що піде в заголовок Reqdate
     * @param userAgent значення User-Agent, яке клієнт надсилає в запиті
     * @return hex-рядок SHA-1 (40 символів, нижній регістр)
     */
    public static String buildSign(String reqDate, String userAgent) {
        String input = SALT + reqDate + SALT + userAgent + SALT;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 гарантовано присутній у будь-якій JVM (java.security)
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}