package io.github.izakyl.folkways.airline;

import java.util.Map;
import net.minecraft.core.BlockPos;

final class AirportMarkings {
    private AirportMarkings() {
    }

    static void runway(Map<BlockPos, Integer> cells, Runway runway) {
        Footprint area = Airport.footprint(runway);
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                BlockPos pos = new BlockPos(x, area.y(), z);
                if (area.contains(pos)) {
                    double d = runway.station(pos);
                    double side = runway.lateral(pos);
                    boolean edge = Math.abs(side) >= runway.width() / 2.0 - 1;
                    boolean keys = (d >= 2 && d < 6 || d > runway.distance() - 6 && d <= runway.distance() - 2)
                        && Math.abs(side) < runway.width() / 2.0 - 2 && Math.round(side) % 2 == 0;
                    boolean center = d > 8 && d < runway.distance() - 8 && Math.abs(side) < 0.65 && (int) d % 10 < 6;
                    cells.put(pos, edge || keys || center ? AirportSurface.WHITE : AirportSurface.GRAY);
                }
            }
        }
    }

    static void pad(Map<BlockPos, Integer> cells, BlockPos center, int size, boolean rotated, boolean thick) {
        int radius = size / 2;
        int along = Math.max(2, radius - 2);
        int across = Math.max(1, (int) ((along * 2 + 1) * 0.6) / 2);
        int stroke = thick ? 3 : 1;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int a = rotated ? z : x;
                int b = rotated ? x : z;
                boolean white = Math.abs(x) == radius || Math.abs(z) == radius
                    || Math.abs(a) <= across && Math.abs(b) <= along
                    && (Math.abs(a) > across - stroke || Math.abs(b) < stroke);
                cells.put(center.offset(x, 0, z), white ? AirportSurface.WHITE : AirportSurface.GRAY);
            }
        }
    }
}
