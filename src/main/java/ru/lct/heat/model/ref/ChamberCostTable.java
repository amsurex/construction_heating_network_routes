package ru.lct.heat.model.ref;

/** Таблица 3.2 Техприложения: стоимость новой камеры и врезки. */
public final class ChamberCostTable {

    /** Стоимость одной врезки в существующую тепловую камеру, руб. */
    public static final double TIE_IN_COST = 5_000_000;

    private ChamberCostTable() {
    }

    /** Стоимость новой камеры по наибольшему ДУ примыкающих участков, руб. */
    public static double newChamberCost(int maxDiameter) {
        if (maxDiameter <= 200) {
            return 3_000_000;
        }
        if (maxDiameter <= 500) {
            return 5_000_000;
        }
        if (maxDiameter <= 1000) {
            return 8_000_000;
        }
        return 12_000_000;
    }
}
