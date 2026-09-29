package ru.lct.heat.routing.stub;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.*;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.*;
import ru.lct.heat.model.out.*;
import ru.lct.heat.model.ref.*;
import ru.lct.heat.routing.*;
import java.util.*;

/** Заглушка V6 только для проверки транспорта. Препятствия намеренно не обходятся. */
public class StubRoutingEngine implements RoutingEngine {
    @Override
    public List<Variant> solve(InputModel input, SolveOptions options) {
        STRtree index = new STRtree();
        for (ExistingPipe p : input.getPipes()) { index.insert(p.getGeometry().getEnvelopeInternal(), p); }
        index.build();
        Variant.VariantBuilder v = Variant.builder().variantId("stub")
                .description("STUB: проверка конвейера; результат не предназначен для сдачи");
        double pipesCost = 0, chambersCost = 0, length = 0, penalty = 0;
        List<Object> unconnected = new ArrayList<>();
        int n = 0;
        ItemDistance distance = (a, b) -> geometry(a.getItem()).distance(geometry(b.getItem()));
        for (ConnectionPoint point : input.getConnectionPoints()) {
            ExistingPipe existing = (ExistingPipe) index.nearestNeighbour(point.getGeometry().getEnvelopeInternal(),
                    point.getGeometry(), distance);
            Coordinate snap = DistanceOp.nearestPoints(existing.getGeometry(), point.getGeometry())[0];
            LineString line = Crs.UTM.createLineString(new Coordinate[]{snap, point.getGeometry().getCoordinate()});
            Optional<DiameterSpec> row = DiameterTable.minByFlowAndLength(point.getFlowTph(), line.getLength());
            if (row.isEmpty() || line.getLength() == 0) {
                unconnected.add(point.getId());
                penalty += CostConstants.unconnectedPenalty(point.getFlowTph());
                continue;
            }
            n++;
            DiameterSpec dn = row.get();
            int chamberDn = Math.max(dn.getDiameter(), existing.getDiameter());
            double chamberCost = ChamberCostTable.newChamberCost(chamberDn);
            String chamberId = "stub_chamber_" + n;
            v.chamber(NewChamber.builder().id(chamberId).geometry(Crs.UTM.createPoint(snap))
                    .diameter(chamberDn).cost(chamberCost).tieInPipeId(existing.getId()).build());
            Double depth = options.getMode() == SolveOptions.Mode.DEPTH_3D ? CostConstants.DEFAULT_DEPTH_M : null;
            double cost = line.getLength() * dn.getCostPerMeter();
            v.pipe(NewPipe.builder().id("stub_net_" + n).startNodeId(chamberId).endNodeId(point.getId())
                    .geometry(line).flowTph(point.getFlowTph()).diameter(dn.getDiameter()).length(line.getLength())
                    .layingMethod(LayingMethod.BASE).depthStart(depth).depthEnd(depth)
                    .kSpecial(1).kDepth(1).cost(cost).build());
            pipesCost += cost;
            chambersCost += chamberCost;
            length += line.getLength();
        }
        double construction = pipesCost + chambersCost;
        v.summary(VariantSummary.builder().id("stub_summary").rank(1).constructionCost(construction)
                .chamberConstructionCost(chambersCost).unconnectedPenalty(penalty)
                .calculatedCost(construction + penalty).newNetworkLength(length)
                .score(CostConstants.score(construction + penalty, length)).unconnectedOksIds(unconnected).build());
        return List.of(v.build());
    }

    private static Geometry geometry(Object item) {
        return item instanceof ExistingPipe ? ((ExistingPipe) item).getGeometry() : (Geometry) item;
    }
}
