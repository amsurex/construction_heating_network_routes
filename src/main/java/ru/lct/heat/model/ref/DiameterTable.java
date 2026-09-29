package ru.lct.heat.model.ref;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Таблица 1 Техприложения: ДУ, пропускная способность, предельная длина, стоимость, габарит. */
public final class DiameterTable {

    private static final List<DiameterSpec> ROWS = Collections.unmodifiableList(Arrays.asList(
            new DiameterSpec(50, 3.5, 181, 74_023, 0.400, 0.125),
            new DiameterSpec(65, 8.3, 245, 78_631, 0.430, 0.140),
            new DiameterSpec(80, 13.2, 327, 83_530, 0.470, 0.160),
            new DiameterSpec(100, 22.3, 419, 89_748, 0.510, 0.180),
            new DiameterSpec(125, 40.2, 554, 97_275, 0.600, 0.225),
            new DiameterSpec(150, 65.1, 696, 105_507, 0.650, 0.250),
            new DiameterSpec(200, 152.3, 1_042, 120_275, 0.880, 0.315),
            new DiameterSpec(250, 274.9, 1_379, 135_323, 1.050, 0.400),
            new DiameterSpec(300, 437.4, 1_718, 150_022, 1.150, 0.450),
            new DiameterSpec(400, 943.1, 2_477, 190_299, 1.370, 0.560),
            new DiameterSpec(500, 1_663.4, 3_245, 224_137, 1.670, 0.710),
            new DiameterSpec(600, 2_627.7, 4_037, 264_790, 1.850, 0.800),
            new DiameterSpec(700, 3_735.1, 4_775, 324_298, 2.050, 0.900),
            new DiameterSpec(800, 5_296.8, 5_644, 325_996, 2.250, 1.000),
            new DiameterSpec(900, 7_165.0, 6_518, 327_693, 2.450, 1.100),
            new DiameterSpec(1000, 9_391.8, 7_419, 418_777, 2.650, 1.200),
            new DiameterSpec(1200, 15_012.8, 9_288, 428_074, 3.100, 1.425),
            new DiameterSpec(1400, 22_501.9, 11_276, 683_417, 3.450, 1.600)
    ));

    private DiameterTable() {
    }

    public static List<DiameterSpec> rows() {
        return ROWS;
    }

    public static Optional<DiameterSpec> byDiameter(int diameter) {
        return ROWS.stream().filter(r -> r.getDiameter() == diameter).findFirst();
    }

    /** Минимальный ДУ, пропускающий заданный расход. */
    public static Optional<DiameterSpec> minByFlow(double flowTph) {
        return ROWS.stream().filter(r -> r.getCapacityTph() >= flowTph).findFirst();
    }

    /** Минимальный ДУ, удовлетворяющий и расходу, и предельной длине. */
    public static Optional<DiameterSpec> minByFlowAndLength(double flowTph, double pathLengthM) {
        return ROWS.stream()
                .filter(r -> r.getCapacityTph() >= flowTph && r.getMaxLengthM() >= pathLengthM)
                .findFirst();
    }

    /** Следующий ДУ по номенклатуре (200 → 250), либо empty для последнего. */
    public static Optional<DiameterSpec> next(DiameterSpec spec) {
        int i = ROWS.indexOf(spec);
        return i >= 0 && i + 1 < ROWS.size() ? Optional.of(ROWS.get(i + 1)) : Optional.empty();
    }

    /** Предыдущий ДУ по номенклатуре (250 → 200), либо empty для первого. */
    public static Optional<DiameterSpec> previous(DiameterSpec spec) {
        int i = ROWS.indexOf(spec);
        return i > 0 ? Optional.of(ROWS.get(i - 1)) : Optional.empty();
    }
}
