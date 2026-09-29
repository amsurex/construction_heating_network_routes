package ru.lct.heat.routing;

import lombok.extern.slf4j.Slf4j;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.model.ref.RuleProfile;
import ru.lct.heat.model.ref.RuleProfiles;
import ru.lct.heat.variants.VariantGenerator;

import java.util.List;

/**
 * Ядро трассировки: варианты строятся {@link VariantGenerator} по разным стратегиям
 * (жадное дерево с общими магистралями {@link TreeBuilder}, расходы/ДУ {@code FlowSizer},
 * ремонт под итоговые ДУ {@link NetworkRepairer}, спецучастки {@link SpecialSplitter}),
 * ранжируются по score, возвращаются до {@code maxVariants} содержательно разных.
 * В режиме DEPTH_3D после спецучастков строится профиль глубины ({@code DepthPlanner}).
 */
@Slf4j
public final class HeatRoutingEngine implements RoutingEngine {

    /** Версия алгоритма — пишется в variant_summary.engine_version; менять при изменении правил/эвристик. */
    public static final String VERSION = "1.0.0";

    private final RoutingParams params;

    public HeatRoutingEngine(RoutingParams params) {
        this.params = params;
    }

    @Override
    public List<Variant> solve(InputModel input, SolveOptions options) {
        long t0 = System.currentTimeMillis();
        boolean depth = options.getMode() == SolveOptions.Mode.DEPTH_3D;
        RuleProfile profile = RuleProfiles.load(options.getResource());
        log.info("Профиль правил: {} — {}", profile.getName(), profile.getDescription());
        InputModel snapped = InputCropper.snapChambers(input, params.getChamberSnapTolM());
        InputModel scoped = params.isCropToAreaOfInterest()
                ? InputCropper.crop(snapped, params.getAreaMarginM(), params.getAreaMinExtentM()).getInput()
                : snapped;
        if (!options.getExcludeRestrictionIds().isEmpty()) {
            List<ru.lct.heat.model.Restriction> kept = new java.util.ArrayList<>();
            for (ru.lct.heat.model.Restriction r : scoped.getRestrictions()) {
                if (!options.getExcludeRestrictionIds().contains(String.valueOf(r.getId()))) {
                    kept.add(r);
                }
            }
            log.info("Режим эксперта: исключено ограничений {}", scoped.getRestrictions().size() - kept.size());
            scoped = scoped.toBuilder().clearRestrictions().restrictions(kept).build();
        }
        List<Variant> variants = new VariantGenerator(scoped, params, depth, profile, options)
                .generate(options.getMaxVariants());
        log.info("Расчёт завершён за {} мс: {} вариантов, лучший score {}", System.currentTimeMillis() - t0,
                variants.size(), variants.isEmpty() ? null : variants.get(0).getSummary().getScore());
        return variants;
    }
}
