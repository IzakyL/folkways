package io.github.izakyl.folkways.core.engine.travel.graph;

import net.minecraft.core.BlockPos;

record Bounds(int westmost, int lowest, int northmost, int eastmost, int highest, int southmost) {

    static Bounds around(int x, int y, int z) {
        return new Bounds(x, y, z, x, y, z);
    }

    Bounds reaching(int x, int y, int z) {
        return new Bounds(Math.min(westmost, x), Math.min(lowest, y), Math.min(northmost, z),
            Math.max(eastmost, x), Math.max(highest, y), Math.max(southmost, z));
    }

    Bounds with(Bounds other) {
        return reaching(other.westmost, other.lowest, other.northmost)
            .reaching(other.eastmost, other.highest, other.southmost);
    }

    boolean holds(BlockPos cell) {
        return cell.getX() >= westmost && cell.getX() <= eastmost
            && cell.getY() >= lowest && cell.getY() <= highest
            && cell.getZ() >= northmost && cell.getZ() <= southmost;
    }
}
