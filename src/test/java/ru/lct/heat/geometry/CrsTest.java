package ru.lct.heat.geometry;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrsTest {

    @Test
    void roundTripMoscow() {
        Coordinate utm = Crs.toUtm(37.6344054041544, 55.6994810644531);
        // UTM 37N для центра Москвы: E ≈ 400–420 км, N ≈ 6.17 млн
        assertEquals(414_000, utm.x, 5_000);
        assertEquals(6_173_000, utm.y, 5_000);
        Coordinate back = Crs.toWgs(utm.x, utm.y);
        assertEquals(37.6344054041544, back.x, 1e-9);
        assertEquals(55.6994810644531, back.y, 1e-9);
    }

    @Test
    void hundredMetersNorthFromSpecExample() {
        // Пример из Техприложения §7.3: участок 100 м между двумя точками
        Coordinate a = Crs.toUtm(37.600000000, 55.750000000);
        Coordinate b = Crs.toUtm(37.599967825, 55.750898263);
        assertEquals(100.0, a.distance(b), 0.05);
    }
}
