package io.github.izakyl.folkways.airline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

public record Airport(String name, BlockPos tower, List<Runway> runways, List<Helipad> helipads,
                      List<Stand> stands, GroundGraph ground, List<Problem> flaws,
                      List<TowerCoverage.Tower> towers, List<TowerCoverage.Facility> coverage) {
    public Airport(String name, BlockPos tower, List<Runway> runways, List<Helipad> helipads,
                   List<Stand> stands, GroundGraph ground, List<Problem> flaws) {
        this(name, tower, runways, helipads, stands, ground, flaws, List.of(), List.of());
    }
    public static final int RADIUS = TowerCoverage.MAX_RADIUS;
    public static final int APPROACH_LENGTH = 128;
    public static final int APPROACH_SLOPE = 20;
    public static final int MAX_SURFACE_BLOCKS = 262144;

    public record Problem(AirportFlaw kind, BlockPos pos, String detail) {
        public Component text() {
            return Component.translatable(kind.translationKey(), detail, pos.toShortString());
        }
    }

    // Facing is the direction of travel into the stop line; dimensions include that line.
    public record Stand(BlockPos pos, Direction facing, int length, int width, int left) {
        public double maxLength() {
            return Math.max(0, length - AirportGrading.MARGIN * 2);
        }

        public double maxWingspan() {
            return Math.max(0, width - AirportGrading.MARGIN * 2);
        }

        public double turningRadius() {
            return Math.max(0, Math.min(length, width) / 2.0 - AirportGrading.MARGIN);
        }

        public Rectangle footprint() {
            Direction across = facing.getClockWise();
            BlockPos first = pos.relative(across, -left);
            BlockPos last = pos.relative(across, width - left - 1).relative(facing.getOpposite(), length - 1);
            return Rectangle.between(first, last);
        }
    }

    public record Rectangle(int minX, int minZ, int maxX, int maxZ, int y) implements Footprint {
        public static Rectangle between(BlockPos first, BlockPos second) {
            return new Rectangle(Math.min(first.getX(), second.getX()), Math.min(first.getZ(), second.getZ()),
                Math.max(first.getX(), second.getX()), Math.max(first.getZ(), second.getZ()), first.getY());
        }

        public boolean contains(BlockPos pos) {
            return pos.getY() == y && pos.getX() >= minX && pos.getX() <= maxX
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        public boolean overlaps(Rectangle other) {
            return y == other.y && minX <= other.maxX && maxX >= other.minX
                && minZ <= other.maxZ && maxZ >= other.minZ;
        }
    }

    public record Airspace(BlockPos threshold, double outward, int length, int slope) {
    }

    public record End(Runway runway, BlockPos threshold, String number, float approachDirection,
                      Airspace approach, Airspace departure) {
    }

    /** Facilities matching a requested footprint. */
    public record Candidates(List<Helipad> helipads, List<Stand> stands) {
        public Candidates {
            helipads = List.copyOf(helipads);
            stands = List.copyOf(stands);
        }
    }

    public Airport {
        runways = List.copyOf(runways);
        helipads = List.copyOf(helipads);
        stands = List.copyOf(stands);
        flaws = List.copyOf(flaws);
        towers = List.copyOf(towers);
        coverage = List.copyOf(coverage);
    }

    public Airport named(String next, List<Problem> problems) {
        return new Airport(next, tower, runways, helipads, stands, ground, problems, towers, coverage);
    }

    public List<End> ends() {
        List<End> ends = new ArrayList<>();
        for (Runway runway : runways) {
            ends.add(end(runway, runway.firstThreshold()));
            ends.add(end(runway, runway.secondThreshold()));
        }
        return List.copyOf(ends);
    }

    private End end(Runway runway, BlockPos pos) {
        double heading = runway.headingAt(pos);
        Airspace corridor = new Airspace(pos, (heading + 180) % 360, APPROACH_LENGTH, APPROACH_SLOPE);
        return new End(runway, pos, designator(runway, pos), (float) (heading - 180), corridor, corridor);
    }

    public String designator(Runway runway, BlockPos pos) {
        int base = Integer.parseInt(runway.numberAt(pos));
        int canonical = (base - 1) % 18 + 1;
        double angle = Math.toRadians(canonical * 10);
        List<Runway> parallel = runways.stream().filter(r -> {
            int n = Integer.parseInt(r.numberAt(r.firstThreshold()));
            return (n - 1) % 18 + 1 == canonical;
        }).sorted(java.util.Comparator.comparingDouble(r ->
            (r.firstThreshold().getX() + r.secondThreshold().getX()) * 0.5 * Math.cos(angle)
                + (r.firstThreshold().getZ() + r.secondThreshold().getZ()) * 0.5 * Math.sin(angle)))
            .toList();
        int index = parallel.indexOf(runway);
        int count = parallel.size();
        if (count == 1) {
            return Runway.number(base);
        }
        boolean reciprocal = base != canonical;
        // Beyond three parallels, group left-to-right in pairs: base, base+1, base-1, base+2, ... .
        // Each reciprocal uses the same shifted number plus 18 and mirrors L/R.
        int group = count <= 3 ? 0 : index / 2;
        int shift = group % 2 == 1 ? (group + 1) / 2 : -group / 2;
        int size = count <= 3 ? count : Math.min(2, count - group * 2);
        int slot = count <= 3 ? index : index % 2;
        String letter = size == 1 ? "" : size == 3 && slot == 1 ? "C"
            : (slot == 0 ^ reciprocal) ? "L" : "R";
        return Runway.number(base + shift) + letter;
    }

    public static Footprint footprint(Runway runway) {
        return new Footprint.Oriented(runway);
    }

    public static Rectangle footprint(Helipad pad) {
        int radius = pad.size() / 2;
        return Rectangle.between(pad.center().offset(-radius, 0, -radius), pad.center().offset(radius, 0, radius));
    }

    public List<Footprint> footprints() {
        List<Footprint> areas = new ArrayList<>();
        runways.forEach(runway -> areas.add(footprint(runway)));
        helipads.forEach(pad -> areas.add(footprint(pad)));
        stands.forEach(stand -> areas.add(stand.footprint()));
        return List.copyOf(areas);
    }

    public Candidates candidates(AirportRequirements needs) {
        if (flaws.stream().anyMatch(problem -> switch (problem.kind()) {
            case UNNAMED, SHARED_NAME, NAME_MISMATCH, NO_LANDING_AREA, UNLOADED, OVERLAP, SURVEY_TOO_LARGE -> true;
            default -> false;
        })) {
            return new Candidates(List.of(), List.of());
        }
        List<Helipad> pads = needs.verticalTakeoff()
            ? helipads.stream().filter(pad -> pad.size() >= needs.helipadSize()).toList() : List.of();
        // A stand is part of the airport when its taxiways reach a runway or a usable pad.
        Set<BlockPos> facilities = new HashSet<>();
        runways.forEach(runway -> facilities.add(runway.firstThreshold()));
        pads.forEach(pad -> facilities.add(pad.center()));
        Set<BlockPos> reachable = ground.reachableFrom(facilities, Math.max(0, needs.standWidth() - 2));
        List<Stand> parking = stands.stream().filter(stand -> stand.length() >= needs.standLength()
            && stand.width() >= needs.standWidth() && reachable.contains(stand.pos())).toList();
        return new Candidates(pads, parking);
    }
}
