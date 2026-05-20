package edu.ilkiv.transit.dto.admin;

import lombok.Data;

/**
 * Тіло запиту для POST /api/admin/routes та PUT /api/admin/routes/{id}.
 * Всі поля опціональні для PUT (патч-семантика).
 *
 * Приклад:
 * {
 *   "name":     "42",
 *   "type":     "BUS",
 *   "color":    "#e74c3c",
 *   "isActive": true
 * }
 */
@Data
public class AdminRouteRequestDto {
    private String  name;
    private String  type;      // BUS | TROLL | TRAM | TAXI | DEFAULT
    private String  color;     // hex, напр. "#FF5733"
    private Boolean isActive;
}