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

const ROUTE_GEOMETRY_MAP = {
    // Тролейбуси (tk: "trol")
    '1_TROLL': 1,
    '2_TROLL': 2,
    '3_TROLL': 4,
    '4_TROLL': 3,
    '5_TROLL': 8,
    '6_TROLL': 7,
    '6A_TROLL': 6,
    '8_TROLL': 75,
    // Автобуси
    '1_BUS':   45,
    '3_BUS':   9,
    '4_BUS':   10,
    '5_BUS':   20,
    '6_BUS':   11,
    '7_BUS':   55,
    '8_BUS':   21,
    '8A_BUS':   81,
    '9_BUS':   82,
    '9A_BUS':   22,
    '10_BUS':   60,
    '10A_BUS':   23,
    '13_BUS':   25,
    '14_BUS':   56,
    '15_BUS':   26,
    '15K_BUS':   27,
    '19_BUS':   30,
    '20_BUS':   61,
    '21_BUS':   32,
    '23_BUS':   34,
    '24_BUS':   58,
    '25_BUS':   57,
    '26_BUS':   14,
    '27_BUS':   36,
    '29_BUS':   15,
    '30_BUS':   16,
    '31_BUS':   18,
    '32_BUS':   40,
    '33_BUS':   53,
    '34_BUS':   41,
    '35_BUS':   62,
    '35A_BUS':   83,
    '36_BUS':   38,
    '37_BUS':   39,
    '39_BUS':   44,
    '41_BUS':   48,
    '43_BUS':   52,
};

function getGeometryFileId(routeName, type) {
    const key = `${routeName}_${type}`;
    return ROUTE_GEOMETRY_MAP[key] ?? null;
}

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
    item.dataset.type = type ?? '';

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
    const id = Number(routeId);
    if (state.selectedRouteIds.has(id)) {
        state.selectedRouteIds.delete(id);
        element.classList.remove('route-item--active');
    } else {
        state.selectedRouteIds.add(id);
        element.classList.add('route-item--active');
    }
    const allItem = document.querySelector('[data-route-id=""]');
    if (allItem) allItem.classList.toggle('route-item--active', state.selectedRouteIds.size === 0);
    updateClearButton();
    applyRouteFilter();
    updateRouteGeometry();
}

function clearSelection() {
    state.selectedRouteIds.clear();
    document.querySelectorAll('.route-item').forEach(el => el.classList.remove('route-item--active'));
    const allItem = document.querySelector('[data-route-id=""]');
    if (allItem) allItem.classList.add('route-item--active');
    updateClearButton();
    applyRouteFilter();
    clearRouteGeometry();
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

    // Знаходимо вибрані маршрути з їх типами
    const selectedRoutes = [...state.selectedRouteIds].map(id =>
        state.routes.find(r => r.id === id)
    ).filter(Boolean);

    return selectedRoutes.some(r =>
        r.name === vehicle.routeName && r.type === vehicle.type
    );
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

document.getElementById('route-search').addEventListener('input', function () {
    const query = this.value.toLowerCase().trim();

    document.querySelectorAll('.route-item').forEach(item => {
        if (item.dataset.routeId === '') return;
        if (item.id === 'clear-selection') return;
        if (!item.dataset.routeId) return;

        const badge = item.querySelector('.route-badge');
        if (!badge) return;
        const span = badge.querySelector('span');
        const routeNum = (span ? span.textContent : badge.textContent).trim().toLowerCase();
        item.style.display = (query === '' || routeNum.startsWith(query)) ? '' : 'none';
    });

    document.querySelectorAll('.route-section-header').forEach(header => {
        let next = header.nextElementSibling;
        let hasVisible = false;
        while (next && !next.classList.contains('route-section-header')) {
            if (next.dataset.routeId &&
                next.id !== 'clear-selection' &&
                next.style.display !== 'none') {
                hasVisible = true;
            }
            next = next.nextElementSibling;
        }
        header.style.display = hasVisible ? '' : 'none';
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
    <line x1="${cx-8}" y1="${cy+r+2}" x2="${cx-8}" y2="${cy+r+11}"
        stroke="${color}" stroke-width="3" stroke-linecap="round"/>
    <line x1="${cx+8}" y1="${cy+r+2}" x2="${cx+8}" y2="${cy+r+11}"
        stroke="${color}" stroke-width="3" stroke-linecap="round"/>` : '';

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
        iconAnchor: [vb / 2, isTroll ? cy - 8 : cy],
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

// ── Зупинки ───────────────────────────────────────────────────────────────────
const stopMarkers = [];
let stopsLoaded = false;

// Відомі маршрути нашої системи для фільтрації
const OUR_BUS_ROUTES = new Set([
    '1','3','4','5','6','6A','7','8','8А','9','9A','10','10A',
    '13','14','15','15К','19','20','21','23','24','25','26','27',
    '29','30','31','32','33','34','35','35A','36','37','39','41','43'
]);
const OUR_TROL_ROUTES = new Set(['1','2','3','4','5','6','6A','8','8А']);

function stopHasOurRoutes(routes) {
    if (routes.bus) {
        const busRoutes = routes.bus.split(',').map(r => r.trim());
        if (busRoutes.some(r => OUR_BUS_ROUTES.has(r))) return true;
    }
    if (routes.trol) {
        const trollRoutes = routes.trol.split(',').map(r => r.trim());
        if (trollRoutes.some(r => OUR_TROL_ROUTES.has(r))) return true;
    }
    return false;
}

async function loadStops() {
    if (stopsLoaded) return;
    try {
        const res = await fetch('/stops.json');
        const data = await res.json();

        const firstEntry = Object.entries(data)[0];
        console.log('Перша зупинка:', firstEntry);
        console.log('Тип data:', typeof data, Array.isArray(data));

        Object.entries(data).forEach(([id, stop]) => {
            const lat = stop[0] / 1_000_000;
            const lng = stop[1] / 1_000_000;
            const name = stop[2];
            const routes = stop[3] || {};

            // Тимчасово без фільтра — показуємо всі
            if (lat < 47.5 || lat > 49.0 || lng < 24.5 || lng > 27.0) return;

            const marker = L.circleMarker([lat, lng], {
                radius: 5,
                fillColor: '#ffffff',
                color: '#1a73e8',
                weight: 2,
                opacity: 1,
                fillOpacity: 1,
            });

            const busText   = routes.bus  ? `🚌 Автобус: <b>${routes.bus}</b>`    : '';
            const trollText = routes.trol ? `🚎 Тролейбус: <b>${routes.trol}</b>` : '';
            const separator = busText && trollText ? '<br>' : '';

            marker.bindPopup(`
                <div style="min-width:160px">
                    <div style="font-weight:700;margin-bottom:6px">${name}</div>
                    ${busText}${separator}${trollText}
                </div>
            `, { maxWidth: 250 });

            stopMarkers.push(marker);
        });

        stopsLoaded = true;
        updateStopsVisibility();
        console.log(`Завантажено ${stopMarkers.length} зупинок`);
    } catch (e) {
        console.error('Failed to load stops:', e);
    }
}

function updateStopsVisibility() {
    const zoom = map.getZoom();
    // Показуємо зупинки тільки при zoom >= 14 щоб не захаращувати карту
    stopMarkers.forEach(m => {
        if (zoom >= 14) m.addTo(map);
        else map.removeLayer(m);
    });
}

// Показуємо/ховаємо зупинки при зміні зуму
map.on('zoomend', () => {
    if (stopsLoaded) updateStopsVisibility();
    // Завантажуємо при першому наближенні
    if (map.getZoom() >= 14 && !stopsLoaded) loadStops();
});


// ── Геометрія маршрутів ───────────────────────────────────────────────────────
const routeGeometryCache = new Map(); // кеш завантажених геометрій
let activePolylines = []; // поточні активні лінії на карті

function parsePoints(pointsStr) {
    return pointsStr.trim().split(' ').map(pair => {
        const [lat, lng] = pair.split(',').map(Number);
        return [lat, lng];
    });
}

async function loadAndShowRouteGeometry(routeName, type, color) {
    const fileId = getGeometryFileId(routeName, type);
    if (!fileId) return;

    // Завантажуємо з кешу або з файлу
    let data;
    if (routeGeometryCache.has(fileId)) {
        data = routeGeometryCache.get(fileId);
    } else {
        try {
            const res = await fetch(`/routes/${fileId}.json`);
            data = await res.json();
            routeGeometryCache.set(fileId, data);
        } catch (e) {
            console.error(`Failed to load route geometry ${fileId}:`, e);
            return;
        }
    }

    const forwardPoints  = parsePoints(data.points.forward);
    const backwardPoints = parsePoints(data.points.backward);

    // Forward — суцільна лінія
    const forwardLine = L.polyline(forwardPoints, {
        color: color,
        weight: 4,
        opacity: 0.8,
    }).addTo(map);

    // Backward — пунктирна лінія
    const backwardLine = L.polyline(backwardPoints, {
        color: color,
        weight: 3,
        opacity: 0.6,
        dashArray: '8, 6',
    }).addTo(map);

    activePolylines.push(forwardLine, backwardLine);
}

function clearRouteGeometry() {
    activePolylines.forEach(p => map.removeLayer(p));
    activePolylines = [];
}

async function updateRouteGeometry() {
    clearRouteGeometry();

    if (state.selectedRouteIds.size === 0) return;

    for (const routeId of state.selectedRouteIds) {
        const route = state.routes.find(r => r.id === routeId);
        if (!route) continue;
        const color = getRouteColor(route.name, route.type);
        await loadAndShowRouteGeometry(route.name, route.type, color);
    }
}


// ── Nearby search ─────────────────────────────────────────────────────────────
let nearbyMarker = null;
let nearbyCircle = null;
let nearbyRadiusM = 300;
let nearbyActive = false;

const geoBtn = document.getElementById('geo-btn');
const nearbyPanel = document.getElementById('nearby-panel');

geoBtn.addEventListener('click', () => {
    if (nearbyActive) {
        removeNearbyMarker();
    } else {
        placeNearbyMarker(map.getCenter());
    }
});

document.getElementById('nearby-close-btn').addEventListener('click', () => {
    removeNearbyMarker();
});

function placeNearbyMarker(latlng) {
    nearbyActive = true;
    geoBtn.classList.add('geo-btn--active');
    nearbyPanel.style.display = '';

    // Маркер геолокації — перетягується
    nearbyMarker = L.marker(latlng, {
        draggable: true,
        icon: L.divIcon({
            html: `<div style="
                width:36px;height:36px;
                background:var(--color-primary);
                border-radius:50% 50% 50% 0;
                transform:rotate(-45deg);
                border:3px solid white;
                box-shadow:0 2px 8px rgba(0,0,0,.3);
            "></div>`,
            iconSize: [36, 36],
            iconAnchor: [18, 36],
            className: '',
        }),
        zIndexOffset: 1000,
    }).addTo(map);

    // Коло радіуса — його край можна тягнути
    nearbyCircle = L.circle(latlng, {
        radius: nearbyRadiusM,
        color: 'var(--color-primary)',
        fillColor: '#1a73e8',
        fillOpacity: 0.08,
        weight: 2,
        dashArray: '6, 4',
    }).addTo(map);

    // Маркер для зміни радіуса
    const radiusHandle = createRadiusHandle(latlng);

    nearbyMarker.on('drag', e => {
        const pos = e.latlng;
        nearbyCircle.setLatLng(pos);
        updateRadiusHandle(radiusHandle, pos);
        updateNearbyResults(pos);
    });

    nearbyMarker.on('dragend', e => {
        updateNearbyResults(e.target.getLatLng());
    });

    updateNearbyResults(latlng);

    // Зберігаємо handle щоб видалити потім
    nearbyMarker._radiusHandle = radiusHandle;
}

function createRadiusHandle(center) {
    const handleLatLng = getRadiusHandleLatLng(center);
    const handle = L.marker(handleLatLng, {
        draggable: true,
        icon: L.divIcon({
            html: `<div style="
                width:14px;height:14px;
                background:white;
                border:3px solid var(--color-primary);
                border-radius:50%;
                box-shadow:0 1px 4px rgba(0,0,0,.3);
            "></div>`,
            iconSize: [14, 14],
            iconAnchor: [7, 7],
            className: '',
        }),
        zIndexOffset: 999,
    }).addTo(map);

    handle.on('drag', e => {
        const center = nearbyMarker.getLatLng();
        const dist = center.distanceTo(e.latlng);
        nearbyRadiusM = Math.max(50, Math.round(dist));
        nearbyCircle.setRadius(nearbyRadiusM);
        document.getElementById('nearby-radius-label').textContent =
            `(${nearbyRadiusM} м)`;
        updateNearbyResults(center);
    });

    return handle;
}

function getRadiusHandleLatLng(center) {
    // Зміщуємо handle на схід від центра на відстань радіуса
    const earthR = 6371000;
    const dLng = (nearbyRadiusM / earthR) * (180 / Math.PI) / Math.cos(center.lat * Math.PI / 180);
    return L.latLng(center.lat, center.lng + dLng);
}

function updateRadiusHandle(handle, center) {
    handle.setLatLng(getRadiusHandleLatLng(center));
}

function removeNearbyMarker() {
    if (nearbyMarker) {
        if (nearbyMarker._radiusHandle) map.removeLayer(nearbyMarker._radiusHandle);
        map.removeLayer(nearbyMarker);
        nearbyMarker = null;
    }
    if (nearbyCircle) {
        map.removeLayer(nearbyCircle);
        nearbyCircle = null;
    }
    nearbyActive = false;
    geoBtn.classList.remove('geo-btn--active');
    nearbyPanel.style.display = 'none';
}

function updateNearbyResults(latlng) {
    // Шукаємо зупинки в радіусі
    const nearbyRoutes = { bus: new Set(), troll: new Set() };

    stopMarkers.forEach(marker => {
        const dist = latlng.distanceTo(marker.getLatLng());
        if (dist > nearbyRadiusM) return;

        // Дістаємо маршрути з popup
        const popup = marker.getPopup();
        if (!popup) return;
        const content = popup.getContent();

        // Парсимо bus маршрути
        const busMatch = content.match(/Автобус:.*?<b>(.*?)<\/b>/);
        if (busMatch) {
            busMatch[1].split(',').map(r => r.trim()).forEach(r => {
                if (r) nearbyRoutes.bus.add(r);
            });
        }

        // Парсимо trol маршрути
        const trollMatch = content.match(/Тролейбус:.*?<b>(.*?)<\/b>/);
        if (trollMatch) {
            trollMatch[1].split(',').map(r => r.trim()).forEach(r => {
                if (r) nearbyRoutes.troll.add(r);
            });
        }
    });

    renderNearbyResults(nearbyRoutes);
}

function renderNearbyResults(nearbyRoutes) {
    const body = document.getElementById('nearby-panel-body');

    const busRoutes   = [...nearbyRoutes.bus].sort((a, b) =>
        a.localeCompare(b, undefined, { numeric: true }));
    const trollRoutes = [...nearbyRoutes.troll].sort((a, b) =>
        a.localeCompare(b, undefined, { numeric: true }));

    if (busRoutes.length === 0 && trollRoutes.length === 0) {
        body.innerHTML = '<div class="nearby-empty">Маршрутів не знайдено</div>';
        return;
    }

    let html = '';

    if (trollRoutes.length > 0) {
        html += `<div class="nearby-section-title">🚎 Тролейбуси</div><div>`;
        trollRoutes.forEach(name => {
            const color = getRouteColor(name, 'TROLL');
            html += `<span class="nearby-route-chip" style="background:${color}"
                onclick="selectRouteByName('${name}','TROLL')">${name}</span>`;
        });
        html += `</div>`;
    }

    if (busRoutes.length > 0) {
        html += `<div class="nearby-section-title">🚌 Автобуси</div><div>`;
        busRoutes.forEach(name => {
            const color = getRouteColor(name, 'BUS');
            html += `<span class="nearby-route-chip" style="background:${color}"
                onclick="selectRouteByName('${name}','BUS')">${name}</span>`;
        });
        html += `</div>`;
    }

    // Кнопка очищення вибору
    html += `<div style="margin-top:10px;border-top:1px solid var(--color-border);padding-top:10px;">
        <button onclick="clearSelection()" style="
            width:100%;padding:7px;border:1px solid var(--color-border);
            background:none;border-radius:var(--radius);cursor:pointer;
            font-size:13px;color:var(--color-muted);">
            ✕ Скинути вибір маршрутів
        </button>
    </div>`;

    body.innerHTML = html;
}

function selectRouteByName(routeName, type) {
    // Знаходимо маршрут в state.routes і вибираємо його
    const route = state.routes.find(r => r.name === routeName && r.type === type);
    if (!route) return;

    const item = document.querySelector(`[data-route-id="${route.id}"]`);
    if (item) toggleRoute(route.id, item);
}

// ── Route planner ─────────────────────────────────────────────────────────────
const plannerState = {
    from: null,  // { lat, lng }
    to:   null,
    fromMarker: null,
    toMarker:   null,
    pinMode: null, // 'from' | 'to'
    activeWayIndex: 0,
    routePolylines: [],
    ways: [],
};

const plannerPanel = document.getElementById('planner-panel');
const plannerBtn   = document.getElementById('planner-btn');

plannerBtn.addEventListener('click', () => {
    plannerPanel.classList.toggle('open');
    plannerBtn.classList.toggle('active');
});

document.getElementById('planner-close').addEventListener('click', () => {
    plannerPanel.classList.remove('open');
    plannerBtn.classList.remove('active');
    clearPlannerRoute();
});

// Pin mode — клік на карту встановлює точку
document.getElementById('pin-from-btn').addEventListener('click', () => {
    setPinMode('from');
});
document.getElementById('pin-to-btn').addEventListener('click', () => {
    setPinMode('to');
});

function setPinMode(mode) {
    plannerState.pinMode = mode;
    document.getElementById('pin-from-btn').classList.toggle('active', mode === 'from');
    document.getElementById('pin-to-btn').classList.toggle('active', mode === 'to');
    map.getContainer().style.cursor = 'crosshair';
}

map.on('click', e => {
    if (!plannerState.pinMode) return;
    const { lat, lng } = e.latlng;
    setPoint(plannerState.pinMode, lat, lng);
    plannerState.pinMode = null;
    document.getElementById('pin-from-btn').classList.remove('active');
    document.getElementById('pin-to-btn').classList.remove('active');
    map.getContainer().style.cursor = '';
});

function setPoint(type, lat, lng) {
    const label = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
    const isFrom = type === 'from';

    if (isFrom) {
        plannerState.from = { lat, lng };
        document.getElementById('planner-from').value = label;
        if (plannerState.fromMarker) map.removeLayer(plannerState.fromMarker);
        plannerState.fromMarker = L.marker([lat, lng], {
            icon: createPlannerIcon('#34a853'),
            draggable: true,
            zIndexOffset: 1100,
        }).addTo(map);
        plannerState.fromMarker.on('dragend', e => {
            const p = e.target.getLatLng();
            setPoint('from', p.lat, p.lng);
            if (plannerState.from && plannerState.to) searchRoute();
        });
    } else {
        plannerState.to = { lat, lng };
        document.getElementById('planner-to').value = label;
        if (plannerState.toMarker) map.removeLayer(plannerState.toMarker);
        plannerState.toMarker = L.marker([lat, lng], {
            icon: createPlannerIcon('#ea4335'),
            draggable: true,
            zIndexOffset: 1100,
        }).addTo(map);
        plannerState.toMarker.on('dragend', e => {
            const p = e.target.getLatLng();
            setPoint('to', p.lat, p.lng);
            if (plannerState.from && plannerState.to) searchRoute();
        });
    }

    document.getElementById('planner-search-btn').disabled =
        !(plannerState.from && plannerState.to);
}

function createPlannerIcon(color) {
    return L.divIcon({
        html: `<div style="
            width:20px;height:20px;
            background:${color};
            border-radius:50% 50% 50% 0;
            transform:rotate(-45deg);
            border:3px solid white;
            box-shadow:0 2px 6px rgba(0,0,0,.4);
        "></div>`,
        iconSize: [20, 20],
        iconAnchor: [10, 20],
        className: '',
    });
}

document.getElementById('planner-search-btn').addEventListener('click', searchRoute);

async function searchRoute() {
    const { from, to } = plannerState;
    if (!from || !to) return;

    const btn = document.getElementById('planner-search-btn');
    btn.disabled = true;
    btn.textContent = 'Шукаємо...';
    document.getElementById('planner-results').innerHTML =
        '<div style="padding:20px;text-align:center;color:var(--color-muted)">⏳ Пошук маршруту...</div>';

    try {
        const params = new URLSearchParams({
            start_lat: from.lat,
            start_lng: from.lng,
            stop_lat:  to.lat,
            stop_lng:  to.lng,
        });

        const res  = await fetch(`/api/route/compile?${params}`, { method: 'POST' });
        const data = await res.json();

        plannerState.ways = data.ways || [];
        plannerState.activeWayIndex = 0;
        renderWays(plannerState.ways);

        if (plannerState.ways.length > 0) {
            await showWayOnMap(0);
        }
    } catch (e) {
        document.getElementById('planner-results').innerHTML =
            '<div style="padding:20px;text-align:center;color:var(--color-danger)">Помилка пошуку маршруту</div>';
        console.error(e);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Знайти маршрут';
    }
}

function renderWays(ways) {
    if (!ways.length) {
        document.getElementById('planner-results').innerHTML =
            '<div style="padding:20px;text-align:center;color:var(--color-muted)">Маршрут не знайдено</div>';
        return;
    }

    const html = ways.map((way, i) => {
        const segments = way.wayDetails.map(d => {
            if (d.type === 'first' || d.type === 'last') {
                return `<span class="way-segment-walk">🚶 ${d.time} хв</span>`;
            }
            if (d.type === 'route') {
                const color = getRouteColor(d.route, d.route_type === 'trol' ? 'TROLL' : 'BUS');
                const icon  = d.route_type === 'trol' ? '🚎' : '🚌';
                return `<span class="way-segment-chip" style="background:${color}">${icon}${d.route}</span>`;
            }
            if (d.type === 'transfer') {
                return `<span class="way-segment-walk">🔄 пересадка</span>`;
            }
            return '';
        }).join('<span style="color:var(--color-muted);font-size:11px;">→</span>');

        return `<div class="way-card ${i === 0 ? 'active' : ''}" onclick="selectWay(${i})">
            <div class="way-card-header">
                <span class="way-time">⏱ ${way.wayTime} хв</span>
                <span class="way-price">₴${way.wayPrice}</span>
            </div>
            <div class="way-segments">${segments}</div>
        </div>`;
    }).join('');

    document.getElementById('planner-results').innerHTML = html;
}

async function selectWay(index) {
    plannerState.activeWayIndex = index;
    document.querySelectorAll('.way-card').forEach((el, i) => {
        el.classList.toggle('active', i === index);
    });
    await showWayOnMap(index);
}

async function showWayOnMap(index) {
    clearPlannerPolylines();
    const way = plannerState.ways[index];
    if (!way) return;

    const routeDetails = way.wayDetails.filter(d => d.type === 'route');
    if (!routeDetails.length) return;

    try {
        // Формуємо параметри як очікує EasyWay
        const ids    = routeDetails.map(d => d.id).join(',');
        const starts = routeDetails.map(d => d.startPosition).join(',');
        const stops  = routeDetails.map(d => d.stopPosition).join(',');
        const a      = `${plannerState.from.lat},${plannerState.from.lng}`;
        const b      = `${plannerState.to.lat},${plannerState.to.lng}`;

        const body = `ids=${ids}&starts=${starts}&stops=${stops}&a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`;

        const res  = await fetch('/api/route/compile-route', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body,
        });
        const data = await res.json();

        if (!data.routes_points?.length) return;

        for (const routePoints of data.routes_points) {
            if (!routePoints.compile_points?.length) continue;

            const points = routePoints.compile_points.map(p => [
                p.x / 1_000_000,
                p.y / 1_000_000,
            ]);

            const color = getRouteColor(
                routePoints.rn,
                routePoints.tt === 'trol' ? 'TROLL' : 'BUS'
            );

            const line = L.polyline(points, {
                color,
                weight: 6,
                opacity: 0.9,
            }).addTo(map);

            plannerState.routePolylines.push(line);

            // Маркери зупинок
            routePoints.compile_points.filter(p => p.n).forEach(p => {
                const m = L.circleMarker([p.x / 1_000_000, p.y / 1_000_000], {
                    radius: 5,
                    fillColor: 'white',
                    color,
                    weight: 2,
                    fillOpacity: 1,
                }).bindTooltip(p.n, { permanent: false }).addTo(map);
                plannerState.routePolylines.push(m);
            });
        }

    } catch (e) {
        console.error('Failed to load compile route:', e);
    }

    // Підганяємо карту під маршрут — тільки якщо є лінії
    if (plannerState.routePolylines.length > 0) {
        const group = L.featureGroup(plannerState.routePolylines);
        map.fitBounds(group.getBounds().pad(0.1));
    } else {
        // Якщо ліній немає — просто показуємо обидві точки
        map.fitBounds([
            [plannerState.from.lat, plannerState.from.lng],
            [plannerState.to.lat,   plannerState.to.lng],
        ], { padding: [50, 50] });
    }
}
function clearPlannerPolylines() {
    plannerState.routePolylines.forEach(l => map.removeLayer(l));
    plannerState.routePolylines = [];
}

function clearPlannerRoute() {
    clearPlannerPolylines();
    if (plannerState.fromMarker) map.removeLayer(plannerState.fromMarker);
    if (plannerState.toMarker)   map.removeLayer(plannerState.toMarker);
    plannerState.from = null;
    plannerState.to   = null;
    plannerState.fromMarker = null;
    plannerState.toMarker   = null;
    plannerState.ways = [];
    document.getElementById('planner-from').value = '';
    document.getElementById('planner-to').value   = '';
    document.getElementById('planner-results').innerHTML = '';
    document.getElementById('planner-search-btn').disabled = true;
}


// ── Старт ─────────────────────────────────────────────────────────────────────
(async () => {
    await loadRoutes();
    await loadInitialVehicles();
    connectWebSocket();
    // Завантажуємо зупинки якщо вже достатньо наближено
    if (map.getZoom() >= 14) loadStops();
})();