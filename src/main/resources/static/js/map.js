/**
 * CV Transit — map.js
 * Leaflet карта + STOMP WebSocket клієнт.
 */

// ── Константи ─────────────────────────────────────────────────────────────────
const CHERNIVTSI = [48.2921, 25.9358];
const DEFAULT_ZOOM = 13;
const WS_URL = '/ws';
const TOPIC_ALL = '/topic/vehicles';
const API_ROUTES = '/api/routes';
const API_VEHICLES = '/api/vehicles';

// Емодзі за типом транспорту
const TYPE_ICON = { BUS: '🚌', TROLL: '🚎', TRAM: '🚋', TAXI: '🚕', DEFAULT: '🚍' };

// ── Стан ──────────────────────────────────────────────────────────────────────
const state = {
    markers: new Map(),      // vehicleId → Leaflet marker
    routes: [],              // RouteResponseDto[]
    activeRouteId: null,     // фільтр по маршруту (null = всі)
    stompClient: null,
};

// ── Ініціалізація карти ───────────────────────────────────────────────────────
const map = L.map('map', { zoomControl: false }).setView(CHERNIVTSI, DEFAULT_ZOOM);

L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '© <a href="https://openstreetmap.org">OpenStreetMap</a>',
    maxZoom: 19,
}).addTo(map);

// Кнопки зуму праворуч
L.control.zoom({ position: 'bottomright' }).addTo(map);

// ── Завантаження маршрутів ────────────────────────────────────────────────────
async function loadRoutes() {
    try {
        const res = await fetch(API_ROUTES);
        state.routes = await res.json();
        renderRouteList(state.routes);
        document.getElementById('stat-routes').textContent = state.routes.length;
    } catch (err) {
        console.error('Failed to load routes:', err);
    }
}

function renderRouteList(routes) {
    const list = document.getElementById('route-list');
    list.innerHTML = '';

    // Пункт "Всі маршрути"
    const allItem = createRouteItem(null, 'Всі', '', null);
    allItem.classList.add('route-item--active');
    list.appendChild(allItem);

    routes
        .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
        .forEach(route => list.appendChild(
            createRouteItem(route.id, route.name, route.vehicleCount, route.color)
        ));
}

function createRouteItem(id, name, count, color) {
    const item = document.createElement('div');
    item.className = 'route-item';
    item.dataset.routeId = id ?? '';

    const badge = document.createElement('div');
    badge.className = 'route-badge';
    badge.textContent = name;
    badge.style.background = color || 'var(--color-primary)';

    const info = document.createElement('div');
    info.className = 'route-info';
    info.innerHTML = `
        <div class="route-name">${name === 'Всі' ? 'Всі маршрути' : 'Маршрут ' + name}</div>
        <div class="route-count">${count !== null ? count + ' транспорт(и)' : ''}</div>
    `;

    item.appendChild(badge);
    item.appendChild(info);

    item.addEventListener('click', () => selectRoute(id, item));
    return item;
}

function selectRoute(routeId, element) {
    // Знімаємо активний клас з усіх
    document.querySelectorAll('.route-item').forEach(el => el.classList.remove('route-item--active'));
    element.classList.add('route-item--active');

    state.activeRouteId = routeId;

    // Показуємо/ховаємо маркери
    state.markers.forEach((marker, vehicleId) => {
        const vehicle = marker.vehicleData;
        const show = routeId === null || vehicle?.routeId === routeId;
        if (show) marker.addTo(map);
        else map.removeLayer(marker);
    });
}

// ── Пошук маршруту ────────────────────────────────────────────────────────────
document.getElementById('route-search').addEventListener('input', function () {
    const query = this.value.toLowerCase();
    document.querySelectorAll('.route-item').forEach(item => {
        const name = item.querySelector('.route-name').textContent.toLowerCase();
        item.style.display = name.includes(query) ? '' : 'none';
    });
});

// ── WebSocket ─────────────────────────────────────────────────────────────────
function connectWebSocket() {
    const socket = new SockJS(WS_URL);
    state.stompClient = Stomp.over(socket);
    state.stompClient.debug = null; // вимикаємо STOMP логи в консолі

    state.stompClient.connect({}, onConnected, onError);
}

function onConnected() {
    setBadge('connected', '● онлайн');

    state.stompClient.subscribe(TOPIC_ALL, message => {
        const vehicles = JSON.parse(message.body);
        updateMarkers(vehicles);
    });
}

function onError(err) {
    console.error('WebSocket error:', err);
    setBadge('error', '✕ помилка');

    // Повторне підключення через 5 сек
    setTimeout(connectWebSocket, 5000);
}

// ── Маркери на карті ──────────────────────────────────────────────────────────
function updateMarkers(vehicles) {
    const now = new Date().toLocaleTimeString('uk-UA');
    document.getElementById('last-update').textContent = 'Оновлено: ' + now;
    document.getElementById('stat-vehicles').textContent = vehicles.filter(v => v.online).length;

    const receivedIds = new Set();

    vehicles.forEach(vehicle => {
        receivedIds.add(vehicle.vehicleId);

        if (state.markers.has(vehicle.vehicleId)) {
            // Плавно переміщуємо існуючий маркер
            const marker = state.markers.get(vehicle.vehicleId);
            animateMarker(marker, vehicle);
            updateMarkerRotation(marker, vehicle.bearing);
            marker.vehicleData = vehicle;
        } else {
            // Створюємо новий маркер
            const marker = createMarker(vehicle);
            state.markers.set(vehicle.vehicleId, marker);

            // Показуємо тільки якщо відповідає фільтру
            if (state.activeRouteId === null || vehicle.routeId === state.activeRouteId) {
                marker.addTo(map);
            }
        }
    });

    // Видаляємо маркери транспорту що вже не онлайн
    state.markers.forEach((marker, id) => {
        if (!receivedIds.has(id)) {
            map.removeLayer(marker);
            state.markers.delete(id);
        }
    });
}

function createMarker(vehicle) {
    const icon = createVehicleIcon(vehicle);

    const marker = L.marker([vehicle.lat, vehicle.lng], {
        icon,
        title: buildTitle(vehicle),
    });

    marker.vehicleData = vehicle;

    marker.on('click', () => showVehiclePanel(vehicle));

    return marker;
}

function createVehicleIcon(vehicle) {
    const emoji = TYPE_ICON[vehicle.type] || TYPE_ICON.DEFAULT;
    const color = vehicle.color || '#1a73e8';
    const size = 36;

    return L.divIcon({
        html: `<div class="vehicle-marker"
                    style="width:${size}px;height:${size}px;background:${color};"
                    data-bearing="${vehicle.bearing || 0}">
                   ${emoji}
               </div>`,
        iconSize:   [size, size],
        iconAnchor: [size / 2, size / 2],
        className:  '',
    });
}

function updateMarkerRotation(marker, bearing) {
    const el = marker.getElement();
    if (!el) return;
    const inner = el.querySelector('.vehicle-marker');
    if (inner) inner.style.transform = `rotate(${bearing || 0}deg)`;
}

function animateMarker(marker, vehicle) {
    // Leaflet не має вбудованої анімації — просто setLatLng
    // Для плавності можна додати CSS transition на .vehicle-marker
    marker.setLatLng([vehicle.lat, vehicle.lng]);
    marker.setIcon(createVehicleIcon(vehicle));
}

function buildTitle(v) {
    return `Маршрут ${v.routeName || '?'} | борт ${v.busNumber || '?'} | ${v.speed || 0} км/год`;
}

// ── Vehicle detail panel ──────────────────────────────────────────────────────
function showVehiclePanel(vehicle) {
    const panel   = document.getElementById('vehicle-panel');
    const content = document.getElementById('vehicle-panel-content');

    content.innerHTML = `
        <h3>${TYPE_ICON[vehicle.type] || '🚍'} Маршрут ${vehicle.routeName || '—'}</h3>
        <div class="vehicle-detail">
            <div>Борт: <strong>${vehicle.busNumber || '—'}</strong></div>
            <div>Швидкість: <strong>${vehicle.speed ? Math.round(vehicle.speed) + ' км/год' : '—'}</strong></div>
            <div>Напрямок: <strong>${vehicle.bearing ? Math.round(vehicle.bearing) + '°' : '—'}</strong></div>
            <div>Статус: <strong style="color: ${vehicle.online ? 'var(--color-success)' : 'var(--color-danger)'}">
                ${vehicle.online ? '● онлайн' : '○ офлайн'}
            </strong></div>
        </div>
    `;

    panel.classList.remove('vehicle-panel--hidden');
}

document.getElementById('vehicle-panel-close').addEventListener('click', () => {
    document.getElementById('vehicle-panel').classList.add('vehicle-panel--hidden');
});

// ── Утиліти ───────────────────────────────────────────────────────────────────
function setBadge(type, text) {
    const badge = document.getElementById('connection-badge');
    badge.textContent = text;
    badge.className = `badge badge--${type}`;
}

// ── Завантаження початкових даних через REST ──────────────────────────────────
async function loadInitialVehicles() {
    try {
        const res = await fetch(API_VEHICLES);
        const vehicles = await res.json();
        updateMarkers(vehicles.map(v => ({
            vehicleId: v.id,
            routeName: v.routeName,
            color:     v.routeColor,
            type:      v.type,
            lat:       v.lat,
            lng:       v.lng,
            speed:     v.speed,
            bearing:   v.bearing,
            busNumber: v.busNumber,
            online:    v.online,
        })));
    } catch (err) {
        console.error('Failed to load initial vehicles:', err);
    }
}

// ── Старт ─────────────────────────────────────────────────────────────────────
(async () => {
    await loadRoutes();
    await loadInitialVehicles(); // одразу показуємо транспорт, не чекаємо WebSocket
    connectWebSocket();          // потім підключаємось для live оновлень
})();