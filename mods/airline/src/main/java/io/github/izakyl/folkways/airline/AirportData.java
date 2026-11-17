package io.github.izakyl.folkways.airline;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

final class AirportData {
    private AirportData() {
    }

    static CompoundTag write(Airport airport) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("groundGrade", airport.ground().graded() ? 1 : 0);
        tag.putLongArray("pavement", airport.ground().pavement().stream().mapToLong(BlockPos::asLong).toArray());
        tag.putString("name", airport.name());
        tag.putLong("tower", airport.tower().asLong());
        ListTag runways = new ListTag();
        for (Runway runway : airport.runways()) {
            CompoundTag row = new CompoundTag();
            row.putLong("first", runway.firstThreshold().asLong());
            row.putLong("second", runway.secondThreshold().asLong());
            row.putInt("width", runway.width());
            runways.add(row);
        }
        tag.put("runways", runways);
        ListTag pads = new ListTag();
        for (Helipad pad : airport.helipads()) {
            CompoundTag row = new CompoundTag();
            row.putLong("center", pad.center().asLong());
            row.putInt("size", pad.size());
            pads.add(row);
        }
        tag.put("pads", pads);
        ListTag stands = new ListTag();
        for (Airport.Stand stand : airport.stands()) {
            CompoundTag row = new CompoundTag();
            row.putLong("pos", stand.pos().asLong());
            row.putInt("facing", stand.facing().get2DDataValue());
            row.putInt("length", stand.length());
            row.putInt("width", stand.width());
            row.putInt("left", stand.left());
            stands.add(row);
        }
        tag.put("stands", stands);
        ListTag nodes = new ListTag();
        for (GroundGraph.Node node : airport.ground().nodes()) {
            CompoundTag row = new CompoundTag();
            row.putLong("pos", node.pos().asLong());
            int kinds = 0;
            for (GroundGraph.Kind kind : node.kinds()) {
                kinds |= 1 << kind.ordinal();
            }
            row.putInt("kinds", kinds);
            row.putInt("remainingFirst", node.remainingFirst());
            row.putInt("remainingSecond", node.remainingSecond());
            row.putDouble("holdingDistance", node.holdingDistance());
            if (node.facility() != null) {
                row.putLong("facility", node.facility().asLong());
            }
            nodes.add(row);
        }
        tag.put("nodes", nodes);
        ListTag edges = new ListTag();
        for (GroundGraph.Edge edge : airport.ground().edges()) {
            CompoundTag row = new CompoundTag();
            row.putLong("from", edge.from().asLong());
            row.putLong("to", edge.to().asLong());
            row.putLongArray("cells", edge.cells().stream().mapToLong(BlockPos::asLong).toArray());
            row.putDouble("maxWingspan", edge.maxWingspan());
            row.putInt("length", edge.length());
            edges.add(row);
        }
        tag.put("edges", edges);
        ListTag turns = new ListTag();
        for (GroundGraph.Turn turn : airport.ground().turns()) {
            CompoundTag row = new CompoundTag();
            row.putLong("pos", turn.pos().asLong());
            row.putInt("first", turn.first().get2DDataValue());
            row.putInt("second", turn.second().get2DDataValue());
            row.putDouble("maxWingspan", turn.maxWingspan());
            row.putDouble("minRadius", turn.minRadius());
            row.putDouble("maxRadius", turn.maxRadius());
            turns.add(row);
        }
        tag.put("turns", turns);
        ListTag flaws = new ListTag();
        for (Airport.Problem problem : airport.flaws()) {
            CompoundTag row = new CompoundTag();
            row.putString("kind", problem.kind().name());
            row.putLong("pos", problem.pos().asLong());
            row.putString("detail", problem.detail());
            flaws.add(row);
        }
        tag.put("flaws", flaws);
        ListTag towers = new ListTag();
        for (TowerCoverage.Tower tower : airport.towers()) {
            CompoundTag row = new CompoundTag();
            row.putLong("pos", tower.pos().asLong());
            row.putInt("facing", tower.facing().get2DDataValue());
            if (tower.colony() != null) {
                row.putUUID("colony", tower.colony());
            }
            row.putBoolean("staffed", tower.staffed());
            towers.add(row);
        }
        tag.put("towers", towers);
        ListTag coverage = new ListTag();
        for (TowerCoverage.Facility facility : airport.coverage()) {
            CompoundTag row = new CompoundTag();
            var area = facility.area();
            row.putIntArray("area", new int[] {area.minX(), area.minZ(), area.maxX(), area.maxZ(), area.y()});
            if (area instanceof Footprint.Oriented oriented) {
                row.putLong("first", oriented.runway().firstThreshold().asLong());
                row.putLong("second", oriented.runway().secondThreshold().asLong());
                row.putInt("width", oriented.runway().width());
            }
            row.putString("reason", facility.reason());
            ListTag samples = new ListTag();
            for (TowerCoverage.Sample sample : facility.samples()) {
                CompoundTag point = new CompoundTag();
                point.putLong("pos", sample.pos().asLong());
                point.putLongArray("towers", sample.towers().stream().mapToLong(BlockPos::asLong).sorted().toArray());
                samples.add(point);
            }
            row.put("samples", samples);
            coverage.add(row);
        }
        tag.put("coverage", coverage);
        return tag;
    }

    /** Geometry stays on the storage grid while surveying, and is saved relative to its stable frame. */
    static CompoundTag offset(CompoundTag geometry, BlockPos offset) {
        CompoundTag result = geometry.copy();
        translate(result, offset);
        return result;
    }

    private static void translate(CompoundTag tag, BlockPos offset) {
        for (String key : List.copyOf(tag.getAllKeys())) {
            Tag value = tag.get(key);
            if (value instanceof CompoundTag child) {
                translate(child, offset);
            } else if (value instanceof ListTag list) {
                for (Tag entry : list) {
                    if (entry instanceof CompoundTag child) {
                        translate(child, offset);
                    }
                }
            } else if (tag.contains(key, Tag.TAG_LONG)
                && Set.of("tower", "first", "second", "center", "pos", "facility", "from", "to").contains(key)) {
                tag.putLong(key, BlockPos.of(tag.getLong(key)).offset(offset).asLong());
            } else if (tag.contains(key, Tag.TAG_LONG_ARRAY) && Set.of("cells", "towers", "pavement").contains(key)) {
                long[] cells = tag.getLongArray(key);
                for (int index = 0; index < cells.length; index++) {
                    cells[index] = BlockPos.of(cells[index]).offset(offset).asLong();
                }
                tag.putLongArray(key, cells);
            } else if (key.equals("area")) {
                int[] area = tag.getIntArray(key);
                tag.putIntArray(key, new int[] {area[0] + offset.getX(), area[1] + offset.getZ(),
                    area[2] + offset.getX(), area[3] + offset.getZ(), area[4] + offset.getY()});
            }
        }
    }

    static Airport read(CompoundTag tag) {
        List<Runway> runways = new ArrayList<>();
        for (Tag value : tag.getList("runways", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            BlockPos first = BlockPos.of(row.getLong("first"));
            runways.add(new Runway(first, BlockPos.of(row.getLong("second")),
                row.getInt("width")));
        }
        List<Helipad> pads = new ArrayList<>();
        for (Tag value : tag.getList("pads", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            pads.add(new Helipad(BlockPos.of(row.getLong("center")), row.getInt("size")));
        }
        List<Airport.Stand> stands = new ArrayList<>();
        for (Tag value : tag.getList("stands", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            stands.add(new Airport.Stand(BlockPos.of(row.getLong("pos")),
                Direction.from2DDataValue(row.getInt("facing")), row.getInt("length"), row.getInt("width"),
                row.getInt("left")));
        }
        List<GroundGraph.Node> nodes = new ArrayList<>();
        for (Tag value : tag.getList("nodes", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            Set<GroundGraph.Kind> kinds = EnumSet.noneOf(GroundGraph.Kind.class);
            for (GroundGraph.Kind kind : GroundGraph.Kind.values()) {
                if ((row.getInt("kinds") & (1 << kind.ordinal())) != 0) {
                    kinds.add(kind);
                }
            }
            nodes.add(new GroundGraph.Node(BlockPos.of(row.getLong("pos")), kinds,
                row.contains("facility") ? BlockPos.of(row.getLong("facility")) : null,
                row.getInt("remainingFirst"), row.getInt("remainingSecond"), row.getDouble("holdingDistance")));
        }
        List<GroundGraph.Edge> edges = new ArrayList<>();
        for (Tag value : tag.getList("edges", Tag.TAG_COMPOUND)) {
            List<BlockPos> cells = new ArrayList<>();
            for (long pos : ((CompoundTag) value).getLongArray("cells")) {
                cells.add(BlockPos.of(pos));
            }
            CompoundTag row = (CompoundTag) value;
            edges.add(new GroundGraph.Edge(BlockPos.of(row.getLong("from")), BlockPos.of(row.getLong("to")),
                row.getInt("length"), cells, row.getDouble("maxWingspan")));
        }
        List<GroundGraph.Turn> turns = new ArrayList<>();
        for (Tag value : tag.getList("turns", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            turns.add(new GroundGraph.Turn(BlockPos.of(row.getLong("pos")),
                Direction.from2DDataValue(row.getInt("first")), Direction.from2DDataValue(row.getInt("second")),
                row.getDouble("maxWingspan"), row.getDouble("minRadius"), row.getDouble("maxRadius")));
        }
        List<Airport.Problem> flaws = new ArrayList<>();
        for (Tag value : tag.getList("flaws", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            flaws.add(new Airport.Problem(AirportFlaw.valueOf(row.getString("kind")),
                BlockPos.of(row.getLong("pos")), row.getString("detail")));
        }
        List<TowerCoverage.Tower> towers = new ArrayList<>();
        for (Tag value : tag.getList("towers", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            towers.add(new TowerCoverage.Tower(BlockPos.of(row.getLong("pos")),
                Direction.from2DDataValue(row.getInt("facing")), row.hasUUID("colony") ? row.getUUID("colony") : null,
                row.getBoolean("staffed")));
        }
        List<TowerCoverage.Facility> coverage = new ArrayList<>();
        for (Tag value : tag.getList("coverage", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            int[] area = row.getIntArray("area");
            List<TowerCoverage.Sample> samples = new ArrayList<>();
            for (Tag point : row.getList("samples", Tag.TAG_COMPOUND)) {
                CompoundTag sample = (CompoundTag) point;
                Set<BlockPos> seen = new java.util.LinkedHashSet<>();
                for (long pos : sample.getLongArray("towers")) {
                    seen.add(BlockPos.of(pos));
                }
                samples.add(new TowerCoverage.Sample(BlockPos.of(sample.getLong("pos")), seen));
            }
            coverage.add(new TowerCoverage.Facility(row.contains("first")
                ? Airport.footprint(new Runway(BlockPos.of(row.getLong("first")),
                    BlockPos.of(row.getLong("second")), row.getInt("width")))
                : new Airport.Rectangle(area[0], area[1], area[2], area[3], area[4]),
                samples, row.getString("reason")));
        }
        var airport = new Airport(tag.getString("name"), BlockPos.of(tag.getLong("tower")), runways, pads, stands,
            new GroundGraph(nodes, edges, turns, tag.getInt("groundGrade") == 1,
                java.util.Arrays.stream(tag.getLongArray("pavement")).mapToObj(BlockPos::of).toList()), flaws, towers, coverage);
        if (!write(airport).equals(tag)) {
            throw new IllegalArgumentException("Incomplete airport survey");
        }
        return airport;
    }
}
