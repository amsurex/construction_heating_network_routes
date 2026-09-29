package ru.lct.heat.io;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.*;
import org.springframework.stereotype.Component;
import ru.lct.heat.geometry.Crs;
import ru.lct.heat.model.out.*;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** §7: потоковая запись WGS84 с девятью знаками после запятой, без копии всего результата. */
@Component
public class GeoJsonWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    public void write(List<Variant> variants, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) { write(variants, out); }
    }

    /** Записывает результат потоково и прерывается до превышения допустимого размера выгрузки. */
    public void write(List<Variant> variants, Path file, long maxBytes) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            write(variants, new LimitedOutputStream(out, maxBytes));
        }
    }

    public void write(List<Variant> variants, OutputStream out) throws IOException {
        try (JsonGenerator g = JSON.getFactory().createGenerator(out)) {
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeArrayFieldStart("features");
            for (Variant v : variants) {
                for (NewPipe p : v.getPipes()) {
                    feature(g, p.getId(), "heat_network", v.getVariantId(), p.getGeometry());
                    field(g, "start_node_id", p.getStartNodeId());
                    field(g, "end_node_id", p.getEndNodeId());
                    field(g, "flow_tph", p.getFlowTph());
                    field(g, "diameter", p.getDiameter());
                    field(g, "length", p.getLength());
                    field(g, "laying_method", p.getLayingMethod().code());
                    field(g, "depth_start", p.getDepthStart());
                    field(g, "depth_end", p.getDepthEnd());
                    field(g, "cost", p.getCost());
                    field(g, "k_special", p.getKSpecial());
                    field(g, "k_depth", p.getKDepth());
                    field(g, "crossed", p.getCrossedRestrictionTypes());
                    extra(g, p.getExtra(), Set.of("id", "object_type", "variant_id", "start_node_id", "end_node_id",
                            "flow_tph", "diameter", "length", "laying_method", "depth_start", "depth_end", "cost",
                            "k_special", "k_depth", "crossed"));
                    end(g);
                }
                for (NewChamber c : v.getChambers()) {
                    feature(g, c.getId(), "heat_chamber", v.getVariantId(), c.getGeometry());
                    field(g, "diameter", c.getDiameter());
                    field(g, "cost", c.getCost());
                    field(g, "tie_in_pipe_id", c.getTieInPipeId());
                    extra(g, c.getExtra(), Set.of("id", "object_type", "variant_id", "diameter", "cost",
                            "tie_in_pipe_id"));
                    end(g);
                }
                for (TechnicalNode n : v.getTechnicalNodes()) {
                    feature(g, n.getId(), "technical_node", v.getVariantId(), n.getGeometry());
                    field(g, "reason", n.getReason());
                    end(g);
                }
                VariantSummary s = v.getSummary();
                feature(g, s.getId(), "variant_summary", v.getVariantId(), null);
                field(g, "rank", s.getRank());
                field(g, "construction_cost", s.getConstructionCost());
                field(g, "chamber_construction_cost", s.getChamberConstructionCost());
                field(g, "existing_chamber_tie_in_count", s.getExistingChamberTieInCount());
                field(g, "existing_chamber_tie_in_cost", s.getExistingChamberTieInCost());
                field(g, "unconnected_penalty", s.getUnconnectedPenalty());
                field(g, "calculated_cost", s.getCalculatedCost());
                field(g, "new_network_length", s.getNewNetworkLength());
                field(g, "score", s.getScore());
                field(g, "unconnected_oks_ids", s.getUnconnectedOksIds());
                if (!s.getExtra().containsKey("description")) { field(g, "description", v.getDescription()); }
                extra(g, s.getExtra(), Set.of("id", "object_type", "variant_id", "rank", "construction_cost",
                        "chamber_construction_cost", "existing_chamber_tie_in_count", "existing_chamber_tie_in_cost",
                        "unconnected_penalty", "calculated_cost", "new_network_length", "score", "unconnected_oks_ids"));
                end(g);
            }
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    private void feature(JsonGenerator g, Object id, String type, Object variant, Geometry geometry) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeFieldName("geometry");
        if (geometry == null) { g.writeNull(); }
        else {
            g.writeStartObject();
            g.writeStringField("type", geometry.getGeometryType());
            g.writeFieldName("coordinates");
            if (geometry instanceof Point) { coordinate(g, geometry.getCoordinate()); }
            else if (geometry instanceof LineString) {
                g.writeStartArray();
                LineString line = (LineString) geometry;
                for (int i = 0; i < line.getNumPoints(); i++) { coordinate(g, line.getCoordinateN(i)); }
                g.writeEndArray();
            } else { throw new IOException("Недопустимая выходная геометрия: " + geometry.getGeometryType()); }
            g.writeEndObject();
        }
        g.writeObjectFieldStart("properties");
        field(g, "id", id);
        field(g, "object_type", type);
        field(g, "variant_id", variant);
    }

    private void coordinate(JsonGenerator g, Coordinate c) throws IOException {
        Coordinate wgs = Crs.toWgs(c.x, c.y);
        g.writeStartArray();
        g.writeNumber(BigDecimal.valueOf(wgs.x).setScale(9, RoundingMode.HALF_UP));
        g.writeNumber(BigDecimal.valueOf(wgs.y).setScale(9, RoundingMode.HALF_UP));
        g.writeEndArray();
    }

    private void field(JsonGenerator g, String name, Object value) throws IOException {
        if (value instanceof Double && !Double.isFinite((Double) value)) {
            throw new IOException("Неконечное выходное значение " + name);
        }
        g.writeObjectField(name, value);
    }

    private void extra(JsonGenerator g, Map<String, Object> values, Set<String> reserved) throws IOException {
        if (values == null) { return; }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!reserved.contains(entry.getKey())) { field(g, entry.getKey(), entry.getValue()); }
        }
    }

    private void end(JsonGenerator g) throws IOException { g.writeEndObject(); g.writeEndObject(); }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long maxBytes;
        private long written;

        private LimitedOutputStream(OutputStream out, long maxBytes) {
            super(out);
            if (maxBytes < 1) { throw new IllegalArgumentException("maxBytes должен быть положительным"); }
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            reserve(length);
            out.write(values, offset, length);
        }

        private void reserve(int bytes) throws OutputSizeLimitException {
            if (written > maxBytes - bytes) { throw new OutputSizeLimitException(maxBytes); }
            written += bytes;
        }
    }
}
