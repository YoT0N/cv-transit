/**
 * CV Transit — map.js
 * Leaflet карта + STOMP WebSocket клієнт.
 * Підтримує мульти-вибір маршрутів, розділення на тролейбуси/автобуси,
 * статичні кольори маршрутів, іконки з антенами для тролейбусів.
 */

// ── Константи ─────────────────────────────────────────────────────────────────
const CHERNIVTSI   = [48.2921, 25.9358];
const DEFAULT_ZOOM = 13;
const WS_URL       = '/ws';
const TOPIC_ALL    = '/topic/vehicles';
const API_ROUTES   = '/api/routes';
const API_VEHICLES = '/api/vehicles';
const STALE_MS     = 90_000;

// ── Статичні кольори маршрутів ────────────────────────────────────────────────
// Автобуси
const BUS_COLORS = {
    '1':   '#E53935', '3':   '#8E24AA', '4':   '#1E88E5', '5':   '#00897B',
    '6':   '#F4511E', '7':   '#6D4C41', '8':   '#00ACC1', '9':   '#7CB342',
    '9A':  '#C0CA33', '10':  '#039BE5', '10A': '#3949AB', '13':  '#D81B60',
    '14':  '#FB8C00', '15':  '#43A047', '15К': '#00897B', '19':  '#E91E63',
    '20':  '#9C27B0', '21':  '#FF5722', '23':  '#795548', '24':  '#607D8B',
    '25':  '#F06292', '26':  '#AED581', '27':  '#4FC3F7', '29':  '#FFB300',
    '30':  '#26A69A', '31':  '#EF5350', '32':  '#AB47BC', '33':  '#42A5F5',
    '34':  '#26C6DA', '35':  '#66BB6A', '35A': '#8D6E63', '36':  '#FFA726',
    '37':  '#EC407A', '39':  '#7E57C2', '41':  '#29B6F6', '43':  '#9CCC65',
};
// Тролейбуси
const TROLL_COLORS = {
    '1': '#B71C1C', '2': '#880E4F', '3': '#4A148C', '4': '#1A237E',
    '5': '#006064', '6': '#1B5E20', '6A':'#33691E', '8': '#E65100',
    '8А':'#BF360C',
};

function getRouteColor(routeName, type) {
    if (type === 'TROLL') return TROLL_COLORS[routeName] || '#B71C1C';
    return BUS_COLORS[routeName] || '#1a73e8';
}

// ── Стан ──────────────────────────────────────────────────────────────────────
const state = {
    markers:          new Map(),
    lastSeen:         new Map(),
    routes:           [],
    selectedRouteIds: new Set(),
    stompClient:      null,
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
        state.routes = state.routes.filter(r => r.name && r.name !== '?');
        renderRouteList(state.routes);
        document.getElementById('stat-routes').textContent = state.routes.length;
    } catch (err) {
        console.error('Failed to load routes:', err);
    }
}

function renderRouteList(routes) {
    const list = document.getElementById('route-list');
    list.innerHTML = '';

    const trollRoutes = routes.filter(r => r.type === 'TROLL')
        .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
    const busRoutes = routes.filter(r => r.type !== 'TROLL')
        .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));

    // Кнопка "Всі маршрути"
    const allItem = createRouteItem(null, 'Всі', null, null, null);
    allItem.classList.add('route-item--active');
    list.appendChild(allItem);

    // Кнопка "Скинути вибір"
    const clearBtn = document.createElement('div');
    clearBtn.id = 'clear-selection';
    clearBtn.className = 'route-item route-item--clear';
    clearBtn.style.display = 'none';
    clearBtn.innerHTML = `
        <div class="route-badge" style="background:var(--color-muted);font-size:18px;">✕</div>
        <div class="route-info">
            <div class="route-name">Скинути вибір</div>
            <div class="route-count" id="selected-count"></div>
        </div>`;
    clearBtn.addEventListener('click', clearSelection);
    list.appendChild(clearBtn);

    // Тролейбуси
    if (trollRoutes.length > 0) {
        const trollHeader = document.createElement('div');
        trollHeader.className = 'route-section-header';
        trollHeader.innerHTML = `<span class="troll-icon">〰</span> Тролейбуси`;
        list.appendChild(trollHeader);
        trollRoutes.forEach(r => list.appendChild(
            createRouteItem(r.id, r.name, r.vehicleCount, getRouteColor(r.name, r.type), r.type)
        ));
    }

    // Автобуси
    if (busRoutes.length > 0) {
        const busHeader = document.createElement('div');
        busHeader.className = 'route-section-header';
        busHeader.innerHTML = `<span>🚌</span> Автобуси`;
        list.appendChild(busHeader);
        busRoutes.forEach(r => list.appendChild(
            createRouteItem(r.id, r.name, r.vehicleCount, getRouteColor(r.name, r.type), r.type)
        ));
    }
}

function createRouteItem(id, name, count, color, type) {
    const item = document.createElement('div');
    item.className = 'route-item';
    item.dataset.routeId = id ?? '';

    const isTroll = type === 'TROLL';
    const badge = document.createElement('div');
    badge.className = 'route-badge';
    badge.style.background = color || 'var(--color-primary)';

    if (isTroll) {
        // Антени для тролейбуса
        badge.innerHTML = `
            <svg width="36" height="36" viewBox="0 0 36 36" style="position:absolute;top:0;left:0;">
              <line x1="10" y1="0" x2="10" y2="8" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
              <line x1="26" y1="0" x2="26" y2="8" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
            </svg>
            <span style="position:relative;z-index:1;">${name}</span>`;
        badge.style.position = 'relative';
        badge.style.overflow = 'visible';
    } else {
        badge.textContent = name;
    }

    const info = document.createElement('div');
    info.className = 'route-info';
    const typeLabel = isTroll ? 'Тролейбус' : (name === 'Всі' ? 'Всі маршрути' : 'Автобус');
    info.innerHTML = `
        <div class="route-name">${name === 'Всі' ? 'Всі маршрути' : typeLabel + ' ' + name}</div>
        <div class="route-count">${count != null ? count + ' транспорт(и)' : ''}</div>`;

    item.appendChild(badge);
    item.appendChild(info);

    if (id === null) {
        item.addEventListener('click', clearSelection);
    } else {
        item.addEventListener('click', () => toggleRoute(id, item));
    }
    return item;
}

// ── Логіка вибору маршрутів ───────────────────────────────────────────────────
function toggleRoute(routeId, element) {
    if (state.selectedRouteIds.has(routeId)) {
        state.selectedRouteIds.delete(routeId);
        element.classList.remove('route-item--active');
    } else {
        state.selectedRouteIds.add(routeId);
        element.classList.add('route-item--active');
    }
    const allItem = document.querySelector('[data-route-id=""]');
    if (allItem) allItem.classList.toggle('route-item--active', state.selectedRouteIds.size === 0);
    updateClearButton();
    applyRouteFilter();
}

function clearSelection() {
    state.selectedRouteIds.clear();
    document.querySelectorAll('.route-item').forEach(el => el.classList.remove('route-item--active'));
    const allItem = document.querySelector('[data-route-id=""]');
    if (allItem) allItem.classList.add('route-item--active');
    updateClearButton();
    applyRouteFilter();
}

function updateClearButton() {
    const btn   = document.getElementById('clear-selection');
    const count = document.getElementById('selected-count');
    if (!btn) return;
    if (state.selectedRouteIds.size > 0) {
        btn.style.display = '';
        count.textContent = `вибрано ${state.selectedRouteIds.size} маршрут(и)`;
    } else {
        btn.style.display = 'none';
    }
}

function isVehicleVisible(vehicle) {
    if (state.selectedRouteIds.size === 0) return true;
    const selectedNames = new Set(
        [...state.selectedRouteIds].map(id => getRouteNameById(id)).filter(Boolean)
    );
    return vehicle.routeName && selectedNames.has(vehicle.routeName);
}

function applyRouteFilter() {
    state.markers.forEach(marker => {
        if (isVehicleVisible(marker.vehicleData)) marker.addTo(map);
        else map.removeLayer(marker);
    });
}

function getRouteNameById(routeId) {
    const route = state.routes.find(r => r.id === routeId);
    return route ? route.name : null;
}

// ── Пошук маршруту ────────────────────────────────────────────────────────────
document.getElementById('route-search').addEventListener('input', function () {
    const query = this.value.toLowerCase().trim();
    document.querySelectorAll('.route-item').forEach(item => {
        if (!item.dataset.routeId) return; // пропускаємо "Всі" і "Скинути"
        const badge = item.querySelector('.route-badge');
        if (!badge) return;
        // Порівнюємо тільки номер маршруту (текст бейджа), не повну назву
        const routeNum = badge.textContent.trim().toLowerCase();
        item.style.display = (query === '' || routeNum === query) ? '' : 'none';
    });
    // Показуємо/ховаємо заголовки секцій
    document.querySelectorAll('.route-section-header').forEach(header => {
        const next = header.nextElementSibling;
        header.style.display = (next && next.style.display !== 'none') ? '' : 'none';
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
        updateMarkers(JSON.parse(message.body));
    });
}

function onError(err) {
    console.error('WebSocket error:', err);
    setBadge('error', '✕ помилка');
    setTimeout(connectWebSocket, 5000);
}

// ── Маркери ───────────────────────────────────────────────────────────────────
function updateMarkers(vehicles) {
    const now = Date.now();
    document.getElementById('last-update').textContent =
        'Оновлено: ' + new Date().toLocaleTimeString('uk-UA');

    vehicles.forEach(vehicle => {
        state.lastSeen.set(vehicle.vehicleId, now);
        if (state.markers.has(vehicle.vehicleId)) {
            const marker = state.markers.get(vehicle.vehicleId);
            marker.setLatLng([vehicle.lat, vehicle.lng]);
            marker.setIcon(createVehicleIcon(vehicle));
            marker.vehicleData = vehicle;
        } else {
            const marker = createMarker(vehicle);
            state.markers.set(vehicle.vehicleId, marker);
            if (isVehicleVisible(vehicle)) marker.addTo(map);
        }
    });

    state.markers.forEach((marker, id) => {
        if (Date.now() - (state.lastSeen.get(id) ?? 0) > STALE_MS) {
            map.removeLayer(marker);
            state.markers.delete(id);
            state.lastSeen.delete(id);
        }
    });

    document.getElementById('stat-vehicles').textContent = state.markers.size;
}

function createMarker(vehicle) {
    const marker = L.marker([vehicle.lat, vehicle.lng], {
        icon: createVehicleIcon(vehicle),
        title: `${vehicle.type === 'TROLL' ? 'Тролейбус' : 'Автобус'} ${vehicle.routeName || '?'}`,
    });
    marker.vehicleData = vehicle;
    marker.on('click', () => showVehiclePanel(vehicle));
    return marker;
}

function createVehicleIcon(vehicle) {
    const isTroll  = vehicle.type === 'TROLL';
    const color    = vehicle.color || getRouteColor(vehicle.routeName, vehicle.type);
    const route    = vehicle.routeName || '?';
    const bearing  = vehicle.bearing || 0;
    const isMoving = vehicle.speed && vehicle.speed > 2;

    const size   = 30;
    const vb     = size + 40; // простір для стрілки і антен
    const cx     = vb / 2;
    const cy     = vb / 2 + (isTroll ? 8 : 0); // зміщуємо вниз якщо є антени
    const r      = size / 2;
    const arrowH = 16;
    const arrowW = 16;
    const tipY   = cy - r - arrowH;
    const baseY  = cy - r + 2;

    const arrowSvg = isMoving ? `
        <polygon points="${cx},${tipY} ${cx-arrowW/2},${baseY} ${cx+arrowW/2},${baseY}"
            fill="${color}" stroke="white" stroke-width="2" stroke-linejoin="round"/>` : '';

    // Антени тролейбуса — дві вертикальні лінії зверху кружечка
    const antennaSvg = isTroll ? `
        <line x1="${cx-8}" y1="${cy-r-2}" x2="${cx-8}" y2="${cy-r-14}"
            stroke="${color}" stroke-width="3" stroke-linecap="round"/>
        <line x1="${cx+8}" y1="${cy-r-2}" x2="${cx+8}" y2="${cy-r-14}"
            stroke="${color}" stroke-width="3" stroke-linecap="round"/>
        <line x1="${cx-8}" y1="${cy-r-14}" x2="${cx+8}" y2="${cy-r-14}"
            stroke="${color}" stroke-width="2" stroke-linecap="round"/>` : '';

    return L.divIcon({
        html: `<svg width="${vb}" height="${vb}" viewBox="0 0 ${vb} ${vb}"
                    xmlns="http://www.w3.org/2000/svg"
                    style="transform:rotate(${isMoving ? bearing : 0}deg);overflow:visible;display:block;">
                 ${arrowSvg}
                 ${antennaSvg}
                 <circle cx="${cx}" cy="${cy}" r="${r}" fill="${color}" stroke="white" stroke-width="2.5"/>
                 <text x="${cx}" y="${cy+4}"
                       text-anchor="middle"
                       font-size="${route.length > 2 ? 10 : 12}"
                       font-weight="700"
                       font-family="Segoe UI,system-ui,sans-serif"
                       fill="white"
                       style="transform:rotate(-${isMoving ? bearing : 0}deg);
                              transform-origin:${cx}px ${cy}px;">
                   ${route}
                 </text>
               </svg>`,
        iconSize:   [vb, vb],
        iconAnchor: [vb / 2, cy],
        className:  '',
    });
}

// ── Vehicle detail panel ──────────────────────────────────────────────────────
function showVehiclePanel(vehicle) {
    const isTroll = vehicle.type === 'TROLL';
    const color   = vehicle.color || getRouteColor(vehicle.routeName, vehicle.type);
    document.getElementById('vehicle-panel-content').innerHTML = `
        <h3 style="color:${color}">${isTroll ? '🚎 Тролейбус' : '🚌 Автобус'} ${vehicle.routeName || '—'}</h3>
        <div class="vehicle-detail">
            <div>Борт: <strong>${vehicle.busNumber || '—'}</strong></div>
            <div>Швидкість: <strong>${vehicle.speed ? Math.round(vehicle.speed) + ' км/год' : '—'}</strong></div>
            <div>Напрямок: <strong>${vehicle.bearing ? Math.round(vehicle.bearing) + '°' : '—'}</strong></div>
            <div>Статус: <strong style="color:${vehicle.online ? 'var(--color-success)' : 'var(--color-danger)'}">
                ${vehicle.online ? '● онлайн' : '○ офлайн'}
            </strong></div>
        </div>`;
    document.getElementById('vehicle-panel').classList.remove('vehicle-panel--hidden');
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