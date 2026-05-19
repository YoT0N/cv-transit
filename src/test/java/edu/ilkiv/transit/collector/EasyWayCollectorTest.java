package edu.ilkiv.transit.collector;

import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.TransportType;
import edu.ilkiv.transit.service.VehicleAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Тести для EasyWayCollector.
 *
 * Оскільки клас підключається до зовнішнього WebSocket у @PostConstruct,
 * ми тестуємо тільки внутрішню логіку через reflection:
 *   - AES розшифрування
 *   - Protobuf парсинг (processMessage)
 *   - Витяг бортового номера (extractBusNumber)
 *   - Фільтрацію невалідних координат
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EasyWayCollector — unit tests")
class EasyWayCollectorTest {

    private static final byte[] AES_KEY = "k38hdGen3ksqAe3m".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AES_IV  = "k38hdGen3ksqAe3m".getBytes(StandardCharsets.UTF_8);

    @Mock
    VehicleAggregationService aggregationService;

    // Не використовуємо @InjectMocks щоб уникнути @PostConstruct (WebSocket connect)
    EasyWayCollector collector;

    @BeforeEach
    void setUp() throws Exception {
        // Створюємо екземпляр без виклику @PostConstruct
        collector = new EasyWayCollector(aggregationService);
    }

    // ── AES розшифрування ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("AES decrypt")
    class AesDecryptTests {

        @Test
        @DisplayName("Коректно розшифровує AES-128-CBC дані")
        void aesDecrypt_validData_returnsPlaintext() throws Exception {
            byte[] original = "Hello, EasyWay!".getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = EasyWayCollectorTest.aesEncrypt(original);

            byte[] result = EasyWayCollectorTest.invokeAesDecrypt(encrypted);

            assertThat(result).startsWith(original);
        }

        @Test
        @DisplayName("Base64 → AES decrypt pipeline повертає оригінальні байти")
        void aesDecrypt_base64Pipeline_roundTrip() throws Exception {
            byte[] original = new byte[]{0x08, 0x01, 0x12, 0x04, 0x08, 0x05}; // мінімальний protobuf
            byte[] encrypted = EasyWayCollectorTest.aesEncrypt(original);
            String base64 = Base64.getEncoder().encodeToString(encrypted);

            byte[] decoded = Base64.getDecoder().decode(base64);
            byte[] decrypted = EasyWayCollectorTest.invokeAesDecrypt(decoded);

            assertThat(decrypted).startsWith(original);
        }
    }

    // ── extractBusNumber ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractBusNumber")
    class ExtractBusNumberTests {

        @Test
        @DisplayName("Формат '075(6351)' → '6351'")
        void extract_standardFormat_returnsBusNumber() throws Exception {
            assertThat(EasyWayCollectorTest.invokeExtractBusNumber("075(6351)")).isEqualTo("6351");
        }

        @Test
        @DisplayName("Формат '001(0042)' → '0042'")
        void extract_leadingZeros_preservedCorrectly() throws Exception {
            assertThat(EasyWayCollectorTest.invokeExtractBusNumber("001(0042)")).isEqualTo("0042");
        }

        @Test
        @DisplayName("Рядок без дужок повертається як є")
        void extract_noBrackets_returnsOriginal() throws Exception {
            assertThat(EasyWayCollectorTest.invokeExtractBusNumber("12345")).isEqualTo("12345");
        }

        @Test
        @DisplayName("null → null")
        void extract_null_returnsNull() throws Exception {
            assertThat(EasyWayCollectorTest.invokeExtractBusNumber(null)).isNull();
        }

        @Test
        @DisplayName("Порожній рядок повертається як є")
        void extract_emptyString_returnsEmpty() throws Exception {
            assertThat(EasyWayCollectorTest.invokeExtractBusNumber("")).isEqualTo("");
        }

        @Test
        @DisplayName("Тільки дужки '()' → ''")
        void extract_emptyBrackets_returnsEmpty() throws Exception {
            assertThat(EasyWayCollectorTest.invokeExtractBusNumber("()")).isEqualTo("");
        }
    }

    // ── processMessage — фільтрація координат ─────────────────────────────────

    @Nested
    @DisplayName("processMessage — coordinate validation")
    class CoordinateValidationTests {

        @Test
        @DisplayName("Нульові координати (0,0) — vehicle не передається в aggregation")
        void processMessage_zeroCoordinates_notAggregated() throws Exception {
            // Мінімальний protobuf з нульовими координатами
            byte[] msg = buildMinimalProtobuf(0, 0);
            EasyWayCollectorTest.invokeProcessMessage(collector, msg);

            verifyNoInteractions(aggregationService);
        }
    }

    // ── processMessage — маппінг маршрутів ────────────────────────────────────

    @Nested
    @DisplayName("processMessage — route mapping")
    class RouteMappingTests {

        @Test
        @DisplayName("Невідомий routeId — vehicle не передається")
        void processMessage_unknownRouteId_notAggregated() throws Exception {
            byte[] msg = EasyWayCollectorTest.buildProtobufWithVehicle(
                    999, 999 /* не існує в ROUTE_NAMES */,
                    48_275_470L, 25_929_660L, "999(9999)"
            );
            EasyWayCollectorTest.invokeProcessMessage(collector, msg);

            verifyNoInteractions(aggregationService);
        }
    }

    // ── processMessage — поля DTO ─────────────────────────────────────────────

    @Nested
    @DisplayName("processMessage — DTO fields")
    class DtoFieldsTests {

        @Test
        @DisplayName("Порожній масив байтів — нічого не передається")
        void processMessage_emptyBytes_noAggregation() throws Exception {
            EasyWayCollectorTest.invokeProcessMessage(collector, new byte[0]);
            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Один байт — нічого не передається")
        void processMessage_singleByte_noAggregation() throws Exception {
            EasyWayCollectorTest.invokeProcessMessage(collector, new byte[]{0x00});
            verifyNoInteractions(aggregationService);
        }
    }

    // ── Допоміжні методи ──────────────────────────────────────────────────────

    /** Викликає приватний метод aesDecrypt через reflection */
    private static byte[] invokeAesDecrypt(byte[] data) throws Exception {
        Method m = EasyWayCollector.class.getDeclaredMethod("aesDecrypt", byte[].class);
        m.setAccessible(true);
        try {
            return (byte[]) m.invoke(null, (Object) data);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** Викликає приватний метод extractBusNumber через reflection */
    private static String invokeExtractBusNumber(String label) throws Exception {
        Method m = EasyWayCollector.class.getDeclaredMethod("extractBusNumber", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, label);
    }

    /** Викликає приватний метод processMessage через reflection */
    private static void invokeProcessMessage(Object collector, byte[] data) throws Exception {
        Method m = EasyWayCollector.class.getDeclaredMethod("processMessage", byte[].class);
        m.setAccessible(true);
        m.invoke(collector, (Object) data);
    }

    /** AES-128-CBC шифрування для підготовки тестових даних */
    private static byte[] aesEncrypt(byte[] data) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(AES_KEY, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(AES_IV);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(data);
    }

    /**
     * Будує мінімальний protobuf з нульовими координатами
     * для тестування фільтрації.
     */
    private static byte[] buildMinimalProtobuf(long lat, long lng) {
        // field 1 (timestamp) = varint 0
        return new byte[]{0x08, 0x00};
    }

    /**
     * Будує повноцінний protobuf повідомлення EasyWay з одним vehicle.
     *
     * Структура:
     *   Message {
     *     field 1 (varint) = timestamp
     *     field 2 (bytes)  = RouteEntry {
     *       field 1 (varint) = routeId
     *       field 2 (bytes)  = RoutePositions {
     *         field 1 (bytes) = VehiclePosition {
     *           field 6  (varint) = vehicleId
     *           field 7  (string) = vehicleLabel
     *           field 15 (varint) = lat * 1_000_000
     *           field 16 (varint) = lng * 1_000_000
     *         }
     *       }
     *     }
     *   }
     */
    private static byte[] buildProtobufWithVehicle(
            int vehicleId, int routeId, long lat, long lng, String label) {

        // VehiclePosition
        byte[] vehicleBytes = concat(
                field(6, varint(vehicleId)),   // vehicleId
                field(7, lengthDelimited(label.getBytes(StandardCharsets.UTF_8))), // label
                field(8, varint(0)),           // angle
                field(12, varint(0)),          // speed
                field(15, varint(lat)),        // lat * 1_000_000
                field(16, varint(lng))         // lng * 1_000_000
        );

        // RoutePositions { field 1 = VehiclePosition }
        byte[] routePositions = field(1, lengthDelimited(vehicleBytes));

        // RouteEntry { field 1 = routeId, field 2 = RoutePositions }
        byte[] routeEntry = concat(
                field(1, varint(routeId)),
                field(2, lengthDelimited(routePositions))
        );

        // Message { field 1 = timestamp, field 2 = RouteEntry }
        return concat(
                field(1, varint(System.currentTimeMillis() / 1000)),
                field(2, lengthDelimited(routeEntry))
        );
    }

    // ── Мінімальний protobuf builder ──────────────────────────────────────────

    private static byte[] varint(long value) {
        byte[] buf = new byte[10];
        int pos = 0;
        do {
            byte b = (byte) (value & 0x7F);
            value >>>= 7;
            if (value != 0) b |= 0x80;
            buf[pos++] = b;
        } while (value != 0);
        byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }

    private static byte[] lengthDelimited(byte[] data) {
        byte[] lenBytes = varint(data.length);
        byte[] result = new byte[lenBytes.length + data.length];
        System.arraycopy(lenBytes, 0, result, 0, lenBytes.length);
        System.arraycopy(data, 0, result, lenBytes.length, data.length);
        return result;
    }

    /** wireType=0: varint */
    private static byte[] field(int fieldNum, long value) {
        return field(fieldNum, varint(value), 0);
    }

    /** wireType=2: length-delimited */
    private static byte[] field(int fieldNum, byte[] lengthDelimitedValue) {
        return field(fieldNum, lengthDelimitedValue, 2);
    }

    private static byte[] field(int fieldNum, byte[] value, int wireType) {
        byte[] tag = varint((long) fieldNum << 3 | wireType);
        byte[] result = new byte[tag.length + value.length];
        System.arraycopy(tag, 0, result, 0, tag.length);
        System.arraycopy(value, 0, result, tag.length, value.length);
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }
}