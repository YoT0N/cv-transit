package edu.ilkiv.transit.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TransportCvSignature — unit tests")
class TransportCvSignatureTest {

    // ── buildReqDate ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildReqDate — повертає рядок у форматі RFC-1123 UTC")
    void buildReqDate_format_isRfc1123() {
        String date = TransportCvSignature.buildReqDate();

        // Приклад: "Tue, 13 May 2025 17:49:24 GMT"
        assertThat(date).matches(
                "\\w{3}, \\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT"
        );
    }

    @Test
    @DisplayName("buildReqDate — закінчується на ' GMT'")
    void buildReqDate_endsWith_GMT() {
        assertThat(TransportCvSignature.buildReqDate()).endsWith(" GMT");
    }

    @Test
    @DisplayName("buildReqDate — англійські назви місяців і днів")
    void buildReqDate_englishLocale() {
        String date = TransportCvSignature.buildReqDate();
        // Перевіряємо що немає кирилиці
        assertThat(date).doesNotContainPattern("[а-яА-ЯіІєЄїЇ]");
    }

    // ── buildSign ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildSign — повертає 40-символьний HEX SHA-1")
    void buildSign_length_is40() {
        String sign = TransportCvSignature.buildSign(
                TransportCvSignature.buildReqDate(),
                TransportCvSignature.USER_AGENT
        );
        assertThat(sign).hasSize(40);
    }

    @Test
    @DisplayName("buildSign — тільки HEX символи у верхньому регістрі")
    void buildSign_isUpperHex() {
        String sign = TransportCvSignature.buildSign(
                TransportCvSignature.buildReqDate(),
                TransportCvSignature.USER_AGENT
        );
        assertThat(sign).matches("[0-9A-F]{40}");
    }

    @Test
    @DisplayName("buildSign — відомий вектор: reqDate і UA з документації")
    void buildSign_knownVector_matchesExpected() {
        // Підтверджено з реального браузерного запиту Chrome 148
        String reqDate = "Sat, 16 May 2026 13:39:01 GMT";
        String expected = "0A67246DFC82C25CF442F1150828010599723170";

        String actual = TransportCvSignature.buildSign(reqDate, TransportCvSignature.USER_AGENT);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("buildSign — різні reqDate дають різні підписи")
    void buildSign_differentDates_differentSigns() {
        String sign1 = TransportCvSignature.buildSign(
                "Mon, 01 Jan 2024 00:00:00 GMT", TransportCvSignature.USER_AGENT);
        String sign2 = TransportCvSignature.buildSign(
                "Tue, 02 Jan 2024 00:00:00 GMT", TransportCvSignature.USER_AGENT);

        assertThat(sign1).isNotEqualTo(sign2);
    }

    @Test
    @DisplayName("buildSign — різні userAgent дають різні підписи")
    void buildSign_differentUserAgent_differentSigns() {
        String date  = "Sat, 16 May 2026 13:39:01 GMT";
        String sign1 = TransportCvSignature.buildSign(date, TransportCvSignature.USER_AGENT);
        String sign2 = TransportCvSignature.buildSign(date, "OtherAgent/1.0");

        assertThat(sign1).isNotEqualTo(sign2);
    }

    @Test
    @DisplayName("buildSign — детермінований: два виклики з однаковим input дають однаковий результат")
    void buildSign_deterministic() {
        String date = "Sat, 16 May 2026 12:00:00 GMT";
        String s1 = TransportCvSignature.buildSign(date, TransportCvSignature.USER_AGENT);
        String s2 = TransportCvSignature.buildSign(date, TransportCvSignature.USER_AGENT);

        assertThat(s1).isEqualTo(s2);
    }

    // ── USER_AGENT константа ──────────────────────────────────────────────────

    @Test
    @DisplayName("USER_AGENT — містить Chrome та Safari (стандартний Chrome UA)")
    void userAgent_containsExpectedBrowserTokens() {
        assertThat(TransportCvSignature.USER_AGENT)
                .contains("Chrome")
                .contains("Safari")
                .contains("Mozilla/5.0");
    }
}