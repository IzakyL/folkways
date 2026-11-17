package io.github.izakyl.folkways.airline;

import java.util.Locale;
import net.minecraft.core.BlockPos;

public record Runway(BlockPos firstThreshold, BlockPos secondThreshold, int width) {
    public int length() {
        return (int) Math.round(distance()) + 1;
    }

    public int surfaceY() {
        return firstThreshold.getY();
    }

    public double distance() {
        return Math.hypot(secondThreshold.getX() - firstThreshold.getX(),
            secondThreshold.getZ() - firstThreshold.getZ());
    }

    public double dx() {
        return (secondThreshold.getX() - firstThreshold.getX()) / distance();
    }

    public double dz() {
        return (secondThreshold.getZ() - firstThreshold.getZ()) / distance();
    }

    public double heading() {
        return (Math.toDegrees(Math.atan2(dx(), -dz())) + 360) % 360;
    }

    public double headingAt(BlockPos threshold) {
        if (firstThreshold.equals(threshold)) {
            return heading();
        }
        if (secondThreshold.equals(threshold)) {
            return (heading() + 180) % 360;
        }
        throw new IllegalArgumentException("Not a runway threshold: " + threshold);
    }

    public static String number(int number) {
        return String.format(Locale.ROOT, "%02d", Math.floorMod(number - 1, 36) + 1);
    }

    public String numberAt(BlockPos threshold) {
        headingAt(threshold);
        int first = (int) Math.round(heading() / 10);
        return number(first + (threshold.equals(firstThreshold) ? 0 : 18));
    }

    public double station(BlockPos pos) {
        return (pos.getX() - firstThreshold.getX()) * dx() + (pos.getZ() - firstThreshold.getZ()) * dz();
    }

    public double lateral(BlockPos pos) {
        return -(pos.getX() - firstThreshold.getX()) * dz() + (pos.getZ() - firstThreshold.getZ()) * dx();
    }

    public BlockPos cell(double station, double side) {
        return BlockPos.containing(firstThreshold.getX() + 0.5 + dx() * station - dz() * side,
            surfaceY(), firstThreshold.getZ() + 0.5 + dz() * station + dx() * side);
    }

    public String name() {
        return numberAt(firstThreshold) + "/" + numberAt(secondThreshold);
    }
}
