package ru.lct.heat.validation;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.io.*;
import ru.lct.heat.model.*;
import ru.lct.heat.model.out.*;
import ru.lct.heat.model.ref.*;
import ru.lct.heat.routing.SolveOptions;
import org.locationtech.jts.geom.Coordinate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heat.support.TestGeoms.*;

public class OutputValidatorTest {
    @TempDir Path temporary;
    private final OutputValidator validator = new OutputValidator(new OutputGeoJsonReader());

    public static InputModel input() {
        return InputModel.builder().source(new SourceNode("source", Crs.UTM.createPoint(c(-100, 0))))
                .pipe(new ExistingPipe("old", line(-100, 0, 0, 0), 300))
                .chamber(new ExistingChamber("chamber", Crs.UTM.createPoint(c(0, 0))))
                .connectionPoint(new ConnectionPoint(42L, Crs.UTM.createPoint(c(0, 100)), 20)).build();
    }
    public static Variant variant() {
        NewPipe pipe = NewPipe.builder().id("v1_net_1").startNodeId("chamber").endNodeId(42L)
                .geometry(line(0, 0, 0, 100)).diameter(100).flowTph(20).length(100).layingMethod(LayingMethod.BASE)
                .cost(8974800).kSpecial(1).kDepth(1).build();
        return Variant.builder().variantId("v1").pipe(pipe).summary(VariantSummary.builder().id("v1_summary").rank(1)
                .existingChamberTieInCount(1).existingChamberTieInCost(ChamberCostTable.TIE_IN_COST)
                .constructionCost(13974800).calculatedCost(13974800).newNetworkLength(100)
                .score(CostConstants.score(13974800, 100)).unconnectedOksIds(List.of()).build()).build();
    }
    ValidationReport check(InputModel input, Variant variant) {
        return validator.validate(input, List.of(variant), SolveOptions.Mode.PLAN_2D);
    }
    @Test void spec73ExamplePassesIndependentChecks() {
        ValidationReport report = check(input(), variant());
        assertTrue(report.isValid(), report.getDiagnostics().toString());
        assertEquals(0, report.getWarningCount());
        assertTrue(report.getRuleSummary().values().stream().mapToLong(RuleCheckSummary::getChecks).sum() > 0);
        assertTrue(report.getRuleSummary().containsKey("§7"));
    }

    @Test void spec5OutputWriterStopsBeforeConfiguredSizeLimit() {
        Path file = temporary.resolve("limited.geojson");
        assertThrows(OutputSizeLimitException.class, () -> new GeoJsonWriter().write(List.of(variant()), file, 100));
        assertTrue(file.toFile().length() <= 100);
    }

    @Test void spec7WriterRoundTripChecksPersistedBytes() throws Exception {
        Path file = temporary.resolve("result.geojson"); new GeoJsonWriter().write(List.of(variant()), file);
        ValidationReport report = validator.validate(input(), file, SolveOptions.Mode.PLAN_2D);
        assertTrue(report.isValid(), report.getDiagnostics().toString());
        String value = Files.readString(file);
        assertTrue(value.contains("\"depth_start\":null")); assertTrue(value.contains("\"geometry\":null"));
        assertTrue(value.contains("\"end_node_id\":42")); assertTrue(value.matches("(?s).*\\d+\\.\\d{9}.*"));
    }

    @Test void spec7WriterKeepsExplanationProperties() throws Exception {
        Variant original = variant();
        NewPipe pipe = original.getPipes().get(0).toBuilder()
                .extra(Map.of("serves_oks", List.of(42L), "turns", 0)).build();
        VariantSummary summary = original.getSummary().toBuilder()
                .extra(Map.of("description", "Проверочный вариант", "cost_breakdown",
                        Map.of("pipes_by_diameter", Map.of("DN100", Map.of("length_m", 100))))).build();
        Variant v = original.toBuilder().clearPipes().pipe(pipe).summary(summary).build();
        Path file = temporary.resolve("explained.geojson");
        new GeoJsonWriter().write(List.of(v), file);
        JsonNode features = new ObjectMapper().readTree(file.toFile()).path("features");
        assertEquals(42, features.get(0).path("properties").path("serves_oks").get(0).asInt());
        assertEquals(100, features.get(1).path("properties").path("cost_breakdown")
                .path("pipes_by_diameter").path("DN100").path("length_m").asInt());
        assertEquals("Проверочный вариант", features.get(1).path("properties").path("description").asText());
        assertTrue(validator.validate(input(), file, SolveOptions.Mode.PLAN_2D).isValid());
    }

    @ParameterizedTest @ValueSource(strings = {"endpoint", "flow", "length", "diameterLow", "diameterHigh", "cost",
            "special", "depth", "score", "construction", "penalty", "rank", "unconnected", "duplicateUnconnected", "cycle"})
    void spec2To7MutationIsDetected(String mutation) {
        Variant v = variant(); NewPipe p = v.getPipes().get(0); NewPipe.NewPipeBuilder b = p.toBuilder();
        switch (mutation) {
            case "endpoint": b.endNodeId("missing"); break; // §7.2
            case "flow": b.flowTph(40); break; // §2.3
            case "length": b.length(101); break; // §7
            case "diameterLow": b.diameter(50); break; // §2.3
            case "diameterHigh": b.diameter(150); break; // §2.3
            case "cost": b.cost(1); break; // §6
            case "special": b.layingMethod(LayingMethod.SPECIAL); break; // §4
            case "depth": b.depthStart(3.0).depthEnd(3.0); break; // §5
            case "score": v = v.toBuilder().summary(v.getSummary().toBuilder().score(0).build()).build(); break;
            case "construction": v = v.toBuilder().summary(v.getSummary().toBuilder().constructionCost(0).build()).build(); break;
            case "penalty": v = v.toBuilder().summary(v.getSummary().toBuilder().unconnectedPenalty(1).build()).build(); break;
            case "rank": v = v.toBuilder().summary(v.getSummary().toBuilder().rank(2).build()).build(); break;
            case "unconnected": v = v.toBuilder().summary(v.getSummary().toBuilder().unconnectedOksIds(List.of(42L)).build()).build(); break;
            case "duplicateUnconnected": v = v.toBuilder().summary(v.getSummary().toBuilder().unconnectedOksIds(List.of(7,7)).build()).build(); break;
            case "cycle": v = v.toBuilder().pipe(p.toBuilder().id("v1_net_2").build()).build(); break;
            default: fail("Unknown mutation");
        }
        if (!"cycle".equals(mutation)) { v = v.toBuilder().clearPipes().pipe(b.build()).build(); }
        assertFalse(check(input(), v).isValid());
    }

    @ParameterizedTest @ValueSource(strings = {"park", "water", "railway", "social_area", "prohibited_site", "unknown", "oks"})
    void spec4DetectsEveryForbiddenType(String type) {
        InputModel original = input();
        InputModel i = InputModel.builder().sources(original.getSources()).pipes(original.getPipes())
                .chambers(original.getChambers()).connectionPoints(original.getConnectionPoints())
                .restriction(restriction("obstacle", type, box(-10, 40, 10, 60))).build();
        assertTrue(check(i, variant()).getDiagnostics().stream().anyMatch(d -> d.getRule().contains("§4")));
    }

    @Test void spec4ValidatorUsesSelectedResourceAndExcludedRestrictions() {
        InputModel old = input();
        InputModel i = InputModel.builder().sources(old.getSources()).pipes(old.getPipes())
                .chambers(old.getChambers()).connectionPoints(old.getConnectionPoints())
                .restriction(restriction("near-park", "park", box(1.4, 40, 3, 60))).build();
        assertTrue(check(i, variant()).isValid());
        ValidationReport water = validator.validate(i, List.of(variant()),
                SolveOptions.builder().resource("water").build());
        assertFalse(water.isValid());
        ValidationReport excluded = validator.validate(i, List.of(variant()),
                SolveOptions.builder().resource("water").excludeRestrictionIds(Set.of("near-park")).build());
        assertTrue(excluded.isValid(), excluded.getDiagnostics().toString());
    }

    @ParameterizedTest @ValueSource(strings = {"road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network"})
    void spec4DetectsMissingSpecialCrossing(String type) {
        InputModel original = input();
        InputModel i = InputModel.builder().sources(original.getSources()).pipes(original.getPipes())
                .chambers(original.getChambers()).connectionPoints(original.getConnectionPoints())
                .restriction(restriction("crossing", type, line(-10, 50, 10, 50))).build();
        assertTrue(check(i, variant()).getDiagnostics().stream().anyMatch(d -> d.getRule().equals("§4")));
    }

    @Test void spec4CorrectRoadSpecialBoundariesPass() {
        InputModel old = input();
        InputModel i = InputModel.builder().sources(old.getSources()).pipes(old.getPipes()).chambers(old.getChambers())
                .connectionPoints(old.getConnectionPoints()).restriction(restriction("road", "road", box(-10, 40, 10, 60))).build();
        Variant v = variant(); NewPipe proto = v.getPipes().get(0);
        NewPipe a = proto.toBuilder().endNodeId("v1_node_1").geometry(line(0,0,0,37)).length(37).cost(37*89748).build();
        NewPipe b = proto.toBuilder().id("v1_net_2").startNodeId("v1_node_1").endNodeId("v1_node_2")
                .geometry(line(0,37,0,63)).length(26).layingMethod(LayingMethod.SPECIAL).kSpecial(1.6).cost(26*89748*1.6).build();
        NewPipe c = proto.toBuilder().id("v1_net_3").startNodeId("v1_node_2").geometry(line(0,63,0,100)).length(37).cost(37*89748).build();
        double cost = a.getCost()+b.getCost()+c.getCost()+ChamberCostTable.TIE_IN_COST;
        v = v.toBuilder().clearPipes().pipes(List.of(a,b,c))
                .technicalNode(new TechnicalNode("v1_node_1", Crs.UTM.createPoint(new Coordinate(0,37)), "special_boundary"))
                .technicalNode(new TechnicalNode("v1_node_2", Crs.UTM.createPoint(new Coordinate(0,63)), "special_boundary"))
                .summary(v.getSummary().toBuilder().constructionCost(cost).calculatedCost(cost).score(CostConstants.score(cost,100)).build()).build();
        ValidationReport report = check(i,v); assertTrue(report.isValid(), report.getDiagnostics().toString());
    }

    @Test void spec22UsesWarningWhenNearestOksBoundaryIsBlocked() {
        InputModel old = input();
        InputModel blocked = InputModel.builder().sources(old.getSources()).pipes(old.getPipes())
                .chambers(old.getChambers())
                .connectionPoint(new ConnectionPoint(42L, Crs.UTM.createPoint(c(0, 104)), 20))
                .restriction(restriction("building", "oks", box(-20, 90, 20, 110)))
                .restriction(restriction("wall", "park", box(-25, 110, 25, 125))).build();
        NewPipe pipe = variant().getPipes().get(0).toBuilder().geometry(line(0, 0, 0, 104))
                .length(104).cost(104 * 89748).build();
        double cost = pipe.getCost() + ChamberCostTable.TIE_IN_COST;
        Variant v = variant().toBuilder().clearPipes().pipe(pipe)
                .summary(variant().getSummary().toBuilder().constructionCost(cost).calculatedCost(cost)
                        .newNetworkLength(104).score(CostConstants.score(cost, 104)).build()).build();
        ValidationReport warning = check(blocked, v);
        assertTrue(warning.isValid(), warning.getDiagnostics().toString());
        assertEquals(0, warning.getErrorCount());
        assertTrue(warning.getDiagnostics().stream().anyMatch(d -> "§2.2".equals(d.getRule())
                && "WARNING".equals(d.getSeverity())));

        InputModel reachable = InputModel.builder().sources(old.getSources()).pipes(old.getPipes())
                .chambers(old.getChambers()).connectionPoints(blocked.getConnectionPoints())
                .restriction(restriction("building", "oks", box(-20, 90, 20, 110))).build();
        assertFalse(check(reachable, v).isValid());
    }

    @Test void spec22ExemptsContainingSocialAreaOnFinalApproach() {
        InputModel old = input();
        InputModel i = InputModel.builder().sources(old.getSources()).pipes(old.getPipes())
                .chambers(old.getChambers()).connectionPoints(old.getConnectionPoints())
                .restriction(restriction("school", "social_area", box(-20, 90, 20, 110)))
                .restriction(restriction("building", "oks", box(-10, 95, 10, 105))).build();
        ValidationReport report = check(i, variant());
        assertTrue(report.isValid(), report.getDiagnostics().toString());
    }

    @Test void spec4BaseSpecialJointDoesNotFailLinearClearance() {
        InputModel old = input();
        InputModel i = InputModel.builder().sources(old.getSources()).pipes(old.getPipes())
                .chambers(old.getChambers()).connectionPoints(old.getConnectionPoints())
                .restriction(restriction("gas", "gas_pipeline", line(-10, 50, 10, 50))).build();
        Variant original = variant(); NewPipe proto = original.getPipes().get(0);
        NewPipe a = proto.toBuilder().endNodeId("v1_node_1").geometry(line(0, 0, 0, 48))
                .length(48).cost(48 * 89748).build();
        NewPipe b = proto.toBuilder().id("v1_net_2").startNodeId("v1_node_1").endNodeId("v1_node_2")
                .geometry(line(0, 48, 0, 52)).length(4).layingMethod(LayingMethod.SPECIAL)
                .kSpecial(1.25).cost(4 * 89748 * 1.25).build();
        NewPipe c = proto.toBuilder().id("v1_net_3").startNodeId("v1_node_2")
                .geometry(line(0, 52, 0, 100)).length(48).cost(48 * 89748).build();
        double cost = a.getCost() + b.getCost() + c.getCost() + ChamberCostTable.TIE_IN_COST;
        Variant v = original.toBuilder().clearPipes().pipes(List.of(a, b, c))
                .technicalNode(new TechnicalNode("v1_node_1", Crs.UTM.createPoint(c(0, 48)), "special_boundary"))
                .technicalNode(new TechnicalNode("v1_node_2", Crs.UTM.createPoint(c(0, 52)), "special_boundary"))
                .summary(original.getSummary().toBuilder().constructionCost(cost).calculatedCost(cost)
                        .score(CostConstants.score(cost, 100)).build()).build();
        ValidationReport report = check(i, v);
        assertTrue(report.isValid(), report.getDiagnostics().toString());
    }

    @Test void spec4SpecialSectionMayEndAtTieInChamberInsideRoad() {
        InputModel old = input();
        InputModel i = InputModel.builder().sources(old.getSources()).pipes(old.getPipes())
                .chamber(new ExistingChamber("far-chamber", Crs.UTM.createPoint(c(-100, 0))))
                .connectionPoints(old.getConnectionPoints())
                .restriction(restriction("road", "road", box(-10, -10, 10, 10))).build();
        Variant original = variant(); NewPipe proto = original.getPipes().get(0);
        NewPipe special = proto.toBuilder().startNodeId("v1_chamber_1").endNodeId("v1_node_1")
                .geometry(line(0, 0, 0, 13))
                .length(13).layingMethod(LayingMethod.SPECIAL).kSpecial(1.6).cost(13 * 89748 * 1.6).build();
        NewPipe base = proto.toBuilder().id("v1_net_2").startNodeId("v1_node_1")
                .geometry(line(0, 13, 0, 100)).length(87).cost(87 * 89748).build();
        double chamberCost = ChamberCostTable.newChamberCost(300);
        double cost = special.getCost() + base.getCost() + chamberCost;
        Variant v = original.toBuilder().clearPipes().pipes(List.of(special, base))
                .chamber(NewChamber.builder().id("v1_chamber_1").geometry(Crs.UTM.createPoint(c(0, 0)))
                        .diameter(300).cost(chamberCost).tieInPipeId("old").build())
                .technicalNode(new TechnicalNode("v1_node_1", Crs.UTM.createPoint(c(0, 13)), "special_boundary"))
                .summary(original.getSummary().toBuilder().chamberConstructionCost(chamberCost)
                        .existingChamberTieInCount(0).existingChamberTieInCost(0)
                        .constructionCost(cost).calculatedCost(cost)
                        .score(CostConstants.score(cost, 100)).build()).build();
        ValidationReport report = check(i, v);
        assertTrue(report.isValid(), report.getDiagnostics().toString());
    }

    @Test void spec5DetectsSlopeAndMinDepthAndContinuity() {
        Variant v = variant(); NewPipe p = v.getPipes().get(0).toBuilder().depthStart(0.1).depthEnd(20.0).build();
        ValidationReport r = validator.validate(input(), List.of(v.toBuilder().clearPipes().pipe(p).build()), SolveOptions.Mode.DEPTH_3D);
        assertTrue(r.getDiagnostics().stream().filter(d -> d.getRule().equals("§5")).count() >= 3);
    }

    @Test void spec7MissingRequiredFieldAndDuplicateOutputIdsFail() throws Exception {
        Path file = temporary.resolve("bad.geojson"); new GeoJsonWriter().write(List.of(variant()), file);
        String bytes = Files.readString(file).replace("\"depth_start\":null,", ""); Files.writeString(file, bytes);
        assertFalse(validator.validate(input(), file, SolveOptions.Mode.PLAN_2D).isValid());
        new GeoJsonWriter().write(List.of(variant(),variant()), file);
        assertFalse(validator.validate(input(), file, SolveOptions.Mode.PLAN_2D).isValid());
    }

    @Test void spec4EngineCrossingCornerOfTwoRoadsPassesValidator() {
        // ядро: трасса под углом через внутренний угол двух пересекающихся дорог — куски road / road,road / road
        // должны совпасть с интервалами валидатора (в т.ч. с продлением спецучастка по зоне отступа)
        InputModel in = InputModel.builder().source(new SourceNode("s", Crs.UTM.createPoint(c(-200, -60))))
                .pipe(new ExistingPipe("old", line(-200, -60, -40, -60), 800))
                .chamber(new ExistingChamber("ch", Crs.UTM.createPoint(c(-40, -60))))
                .restriction(new Restriction("h", box(-1000, 0, 1000, 14), RestrictionRules.ROAD))
                .restriction(new Restriction("v", box(20, -1000, 34, 1000), RestrictionRules.ROAD))
                .connectionPoint(new ConnectionPoint(1L, Crs.UTM.createPoint(c(70, 60)), 3000)).build();
        ru.lct.heat.routing.RoutingParams params = new ru.lct.heat.routing.RoutingParams();
        params.setEnabledStrategies("v1");
        List<Variant> variants = new ru.lct.heat.routing.HeatRoutingEngine(params).solve(in, SolveOptions.defaults());
        assertFalse(variants.isEmpty());
        ValidationReport report = validator.validate(in, variants, SolveOptions.Mode.PLAN_2D);
        assertTrue(report.isValid(), report.getDiagnostics().toString());
    }

    @Test void spec4SplitPiecesAtCornerOfTwoRoadsMatchValidatorIntervals() {
        // как в grid14: DN700 под 45° через внутренний угол двух пересекающихся дорог; SpecialSplitter даёт
        // куски road / road,road / road с продлением по зоне отступа — валидатор должен видеть те же интервалы
        InputModel in = InputModel.builder().source(new SourceNode("s", Crs.UTM.createPoint(c(-200, -40))))
                .pipe(new ExistingPipe("old", line(-200, -40, -38, -40), 800))
                .chamber(new ExistingChamber("ch", Crs.UTM.createPoint(c(-38, -40))))
                .restriction(new Restriction("h", box(-1000, 0, 1000, 14), RestrictionRules.ROAD))
                .restriction(new Restriction("v", box(20, -1000, 34, 1000), RestrictionRules.ROAD))
                .connectionPoint(new ConnectionPoint(1L, Crs.UTM.createPoint(c(60, 58)), 3000)).build();
        DiameterSpec dn = DiameterTable.byDiameter(700).orElseThrow();
        ru.lct.heat.geometry.ObstacleMap map = ru.lct.heat.geometry.ObstacleMap.build(in, dn, 0.05, 10);
        ru.lct.heat.routing.network.NewNetwork net = new ru.lct.heat.routing.network.NewNetwork();
        Coordinate a = c(-38, -40), b = c(60, 58);
        ru.lct.heat.routing.network.NetNode from = net.addNode("ch", a, ru.lct.heat.routing.network.NetNode.Kind.EXISTING_CHAMBER);
        ru.lct.heat.routing.network.NetNode to = net.addNode(1L, b, ru.lct.heat.routing.network.NetNode.Kind.OKS_POINT);
        ru.lct.heat.routing.network.NetEdge e = net.addEdge(from, to, new ArrayList<>(List.of(a, b)),
                new ArrayList<>(List.of(map.check(a, b, Set.of(), false, true))));
        e.setDiameter(dn); e.setFlowTph(3000);
        new ru.lct.heat.routing.SpecialSplitter().split(net);
        assertTrue(net.getEdges().size() >= 5, "ожидались куски base/road/road,road/road/base: " + net.getEdges().size());
        Variant v = new ru.lct.heat.cost.VariantAssembler("v1", "test").assemble(net, List.of());
        v = v.toBuilder().summary(v.getSummary().toBuilder().rank(1).build()).build();
        ValidationReport report = validator.validate(in, List.of(v), SolveOptions.Mode.PLAN_2D);
        assertTrue(report.isValid(), report.getDiagnostics().toString());
    }
}
