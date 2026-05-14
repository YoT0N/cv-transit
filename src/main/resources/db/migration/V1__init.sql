-- ── Enums ───────────────────────────────────────────────────────────────────
CREATE TYPE transport_type AS ENUM ('BUS', 'TROLL', 'TRAM', 'TAXI', 'DEFAULT');
CREATE TYPE data_source    AS ENUM ('nimbus', 'transgps', 'transportcv', 'easyway');

-- ── routes ──────────────────────────────────────────────────────────────────
-- Канонічна таблиця маршрутів. Один рядок = один реальний маршрут міста.
CREATE TABLE routes (
                        id          BIGSERIAL        PRIMARY KEY,
                        name        VARCHAR(16)      NOT NULL,          -- "10", "19A", "3"
                        type        transport_type   NOT NULL DEFAULT 'BUS',
                        color       VARCHAR(16),                        -- колір маркера на карті
                        is_active   BOOLEAN          NOT NULL DEFAULT TRUE,
                        updated_at  TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_routes_name ON routes(name);

-- ── stops ───────────────────────────────────────────────────────────────────
-- Канонічна таблиця зупинок.
CREATE TABLE stops (
                       id          BIGSERIAL        PRIMARY KEY,
                       name        VARCHAR(256)     NOT NULL,
                       lat         DOUBLE PRECISION NOT NULL,
                       lng         DOUBLE PRECISION NOT NULL,
                       radius_m    FLOAT            NOT NULL DEFAULT 50.0,
                       updated_at  TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stops_location ON stops(lat, lng);

-- ── route_stops ──────────────────────────────────────────────────────────────
-- Які зупинки входять до маршруту і в якому порядку.
CREATE TABLE route_stops (
                             id          BIGSERIAL   PRIMARY KEY,
                             route_id    BIGINT      NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
                             stop_id     BIGINT      NOT NULL REFERENCES stops(id)  ON DELETE CASCADE,
                             stop_order  INTEGER     NOT NULL,   -- порядковий номер зупинки в маршруті
                             arrival_sec INTEGER,                -- секунди від початку доби (з розкладу Nimbus)
                             UNIQUE (route_id, stop_id, stop_order)
);

-- ── vehicles ─────────────────────────────────────────────────────────────────
-- Поточний стан кожного транспортного засобу (оновлюється при кожному polling).
CREATE TABLE vehicles (
                          id          BIGSERIAL        PRIMARY KEY,
                          external_id VARCHAR(64)      NOT NULL,  -- id у джерелі (imei, vehicleId тощо)
                          source      data_source      NOT NULL,
                          route_id    BIGINT           REFERENCES routes(id) ON DELETE SET NULL,
                          bus_number  VARCHAR(16),               -- бортовий номер ("4811", "3459")
                          lat         DOUBLE PRECISION NOT NULL,
                          lng         DOUBLE PRECISION NOT NULL,
                          speed       FLOAT,
                          bearing     FLOAT,
                          is_online   BOOLEAN          NOT NULL DEFAULT TRUE,
                          last_seen   TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
                          UNIQUE (external_id, source)           -- один засіб = один рядок на джерело
);

CREATE INDEX idx_vehicles_route  ON vehicles(route_id);
CREATE INDEX idx_vehicles_online ON vehicles(is_online);

-- ── gps_history ──────────────────────────────────────────────────────────────
-- Архів GPS точок для аналітики затримок і побудови треків.
CREATE TABLE gps_history (
                             id          BIGSERIAL        PRIMARY KEY,
                             vehicle_id  BIGINT           NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
                             lat         DOUBLE PRECISION NOT NULL,
                             lng         DOUBLE PRECISION NOT NULL,
                             speed       FLOAT,
                             source      data_source      NOT NULL,
                             recorded_at TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_gps_history_vehicle ON gps_history(vehicle_id);
CREATE INDEX idx_gps_history_time    ON gps_history(recorded_at DESC);

-- ── source_mappings ──────────────────────────────────────────────────────────
-- Зв'язок між id у зовнішньому джерелі та канонічним id у нашій БД.
-- Дозволяє звести маршрут "10" з transgps (id="19") і nimbus (id="20208")
-- до одного запису routes(id=X).
CREATE TABLE source_mappings (
                                 id           BIGSERIAL    PRIMARY KEY,
                                 entity_type  VARCHAR(16)  NOT NULL,   -- 'route' | 'stop' | 'vehicle'
                                 canonical_id BIGINT       NOT NULL,   -- id у головній таблиці (routes/stops)
                                 source       data_source  NOT NULL,
                                 source_id    VARCHAR(64)  NOT NULL,   -- id як він виглядає у джерелі
                                 UNIQUE (entity_type, source, source_id)
);

CREATE INDEX idx_source_mappings_lookup
    ON source_mappings(entity_type, source, source_id);