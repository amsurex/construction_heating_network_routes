package ru.lct.heat.model.ref;

import lombok.Value;

/** Строка таблицы 1 Техприложения. */
@Value
public class DiameterSpec {
    /** ДУ, мм. */
    int diameter;
    /** Пропускная способность, т/ч. */
    double capacityTph;
    /** Предельная длина непрерывной части одного ДУ, м. */
    double maxLengthM;
    /** Стоимость нового строительства, руб./м. */
    double costPerMeter;
    /** Расчётная ширина пары труб, м. */
    double widthM;
    /** Расчётная высота габарита, м. */
    double heightM;

    public double halfWidth() {
        return widthM / 2.0;
    }
}
