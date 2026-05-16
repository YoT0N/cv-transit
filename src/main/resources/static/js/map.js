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
    document.querySelectorAll('.route-item').forEach(el => el.classList.remove('route-item--active'));
    element.classList.add('route-item--active');
    state.activeRouteId = routeId;

    state.markers.forEach((marker) => {
        const vehicle = marker.vehicleData;
        const show = routeId === null || vehicle?.routeName === getRouteNameById(routeId);
        if (show) marker.addTo(map);
        else map.removeLayer(marker);
    });
}

function getRouteNameById(routeId) {
    const route = state.routes.find(r => r.id === routeId);
    return route ? route.name : null;
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
    state.stompClient.debug = null;
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
            const marker = state.markers.get(vehicle.vehicleId);
            marker.setLatLng([vehicle.lat, vehicle.lng]);
            marker.setIcon(createVehicleIcon(vehicle));
            marker.vehicleData = vehicle;
        } else {
            const marker = createMarker(vehicle);
            state.markers.set(vehicle.vehicleId, marker);
            if (state.activeRouteId === null) {
                marker.addTo(map);
            }
        }
    });

    state.markers.forEach((marker, id) => {
        if (!receivedIds.has(id)) {
            map.removeLayer(marker);
            state.markers.delete(id);
        }
    });
}

function createMarker(vehicle) {
    const marker = L.marker([vehicle.lat, vehicle.lng], {
        icon: createVehicleIcon(vehicle),
        title: `Маршрут ${vehicle.routeName || '?'} | борт ${vehicle.busNumber || '?'}`,
    });
    marker.vehicleData = vehicle;
    marker.on('click', () => showVehiclePanel(vehicle));
    return marker;
}

function createVehicleIcon(vehicle) {
    const color   = vehicle.color || '#1a73e8';
    const route   = vehicle.routeName || '?';
    const bearing = vehicle.bearing || 0;
    const size    = 30;
    const r       = size / 2;
    // Стрілка: великий трикутник зовні кола, обертається разом із SVG
    const arrowH  = 20;  // висота стрілки
    const arrowW  = 20;  // ширина основи стрілки
    // SVG viewBox більший щоб стрілка не обрізалась
    const vb      = size + arrowH * 2;
    const cx      = vb / 2;
    const cy      = vb / 2;
    // Вершина стрілки (вгорі), основа — на межі кола
    const tipY    = cy - r - arrowH;
    const baseY   = cy - r + 2;

    return L.divIcon({
        html: `<svg width="${vb}" height="${vb}" viewBox="0 0 ${vb} ${vb}" xmlns="http://www.w3.org/2000/svg"
                    style="transform:rotate(${bearing}deg); overflow:visible; display:block;">
                 <!-- стрілка -->
                 <polygon
                   points="${cx},${tipY} ${cx - arrowW/2},${baseY} ${cx + arrowW/2},${baseY}"
                   fill="${color}"
                   stroke="white"
                   stroke-width="2"
                   stroke-linejoin="round"/>
                 <!-- коло -->
                 <circle cx="${cx}" cy="${cy}" r="${r}" fill="${color}" stroke="white" stroke-width="2.5"/>
                 <!-- текст маршруту (counter-rotate щоб не крутився разом) -->
                 <text x="${cx}" y="${cy + 4}"
                       text-anchor="middle"
                       font-size="${route.length > 2 ? 11 : 13}"
                       font-weight="700"
                       font-family="Segoe UI, system-ui, sans-serif"
                       fill="white"
                       style="transform:rotate(-${bearing}deg); transform-origin:${cx}px ${cy}px;">
                   ${route}
                 </text>
               </svg>`,
        iconSize:   [vb, vb],
        iconAnchor: [vb / 2, vb / 2],
        className:  '',
    });
}

// ── Vehicle detail panel ──────────────────────────────────────────────────────
function showVehiclePanel(vehicle) {
    const panel   = document.getElementById('vehicle-panel');
    const content = document.getElementById('vehicle-panel-content');

    content.innerHTML = `
        <h3>🚌 Маршрут ${vehicle.routeName || '—'}</h3>
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

// ── Початкові дані через REST ─────────────────────────────────────────────────
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
    await loadInitialVehicles();
    connectWebSocket();
})();