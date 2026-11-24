package io.github.izakyl.folkways.solve;

import io.github.izakyl.folkways.solve.Chains.Chain;
import io.github.izakyl.folkways.solve.Chains.Options;
import io.github.izakyl.folkways.solve.Chains.Prismatic;
import io.github.izakyl.folkways.solve.Chains.Revolute;
import io.github.izakyl.folkways.solve.Chains.Sample;
import io.github.izakyl.folkways.solve.Chains.Vec;
import io.github.izakyl.folkways.solve.Kinematics.Range;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ChainsTest {

    private static final Vec ORIGIN = new Vec(0, 0, 0);
    private static final Vec X = new Vec(1, 0, 0);
    private static final Vec Z = new Vec(0, 0, 1);

    @Test
    public void findsReachablePlanarTargetsAndReportsDistanceForUnreachableTargets() {
        var arm = planar(null);
        var reachable = Chains.solve(arm, X, null, Options.DEFAULT);
        assertTrue(reachable.reached());
        assertEquals(0, Chains.forward(arm, reachable.values()).tip().subtract(X).length(), 1e-4);

        var unreachable = Chains.solve(arm, new Vec(6, 8, 0), null, Options.DEFAULT);
        assertFalse(unreachable.reached());
        assertEquals(8, unreachable.residual(), 1e-4);
    }

    @Test
    public void respectsRevoluteAndPrismaticLimits() {
        var arm = new Chain(List.of(new Prismatic(Z, new Range(0, 1)),
            new Revolute(ORIGIN, Z, new Range(0, Math.PI / 4))), X);
        var result = Chains.solve(arm, new Vec(0, 1, 4), List.of(10.0, -8.0), Options.DEFAULT);
        assertFalse(result.reached());
        assertEquals(1, result.values().get(0), 0);
        assertEquals(Math.PI / 4, result.values().get(1), 0);
    }

    @Test
    public void solvesMixedThreeDimensionalChainWithToolDirection() {
        var arm = new Chain(List.of(new Prismatic(Z, new Range(-2, 4)),
            new Revolute(new Vec(1, 2, 3), Z, null)), new Vec(3, 2, 4));
        var direction = new Vec(0, 1, 0);
        var target = new Vec(1, 4, 7);
        var result = Chains.solve(arm, target, null, new Options(144, 1e-4, direction, 2));
        assertTrue(result.reached());
        assertEquals(3, result.values().get(0), 1e-4);
        assertEquals(Math.PI / 2, result.values().get(1), 1e-4);
        assertEquals(0, Chains.forward(arm, result.values()).direction().subtract(direction).length(), 1e-4);
    }

    @Test
    public void roundTripsRandomReachableTargets() {
        var arm = planar(null);
        var random = new Random(734);
        for (int i = 0; i < 160; i++) {
            var values = List.of(random.nextDouble(-Math.PI, Math.PI), random.nextDouble(-Math.PI, Math.PI));
            Vec target = Chains.forward(arm, values).tip();
            var solution = Chains.solve(arm, target, null, Options.DEFAULT);
            assertTrue("Target " + target + " residual " + solution.residual(), solution.reached());
            assertEquals(0, Chains.forward(arm, solution.values()).tip().subtract(target).length(), 1e-4);
        }
    }

    @Test
    public void fitsNoisyRevoluteSamples() {
        var pivot = new Vec(21, -6, 13);
        var axis = new Vec(1.0 / 3, 2.0 / 3, 2.0 / 3);
        var arm = new Chain(List.of(new Revolute(pivot, axis, null)), pivot.add(new Vec(4, -2, 0)));
        var random = new Random(113);
        var samples = new ArrayList<Sample>();
        for (int i = 0; i < 24; i++) {
            Vec point = Chains.forward(arm, List.of(-0.28 * i)).tip();
            samples.addFirst(new Sample(17 + i, point.add(noise(random))));
        }

        var fit = Chains.fitRevolute(samples);
        var rotation = (Revolute) fit.joint();
        assertEquals(17, fit.zero(), 0);
        assertEquals(0, rotation.pivot().subtract(pivot).length(), 0.004);
        assertEquals(1, Math.abs(rotation.axis().dot(axis)), 1e-5);
        assertEquals(-0.28, fit.perUnit() * rotation.axis().dot(axis), 0.001);
        assertEquals(0, fit.reference().subtract(arm.tip()).length(), 0.004);
        assertTrue(fit.residual() < 0.008);
    }

    @Test
    public void fitsNoisyPrismaticSamples() {
        var axis = new Vec(-2.0 / 3, 1.0 / 3, 2.0 / 3);
        var reference = new Vec(5, -3, 24);
        var random = new Random(114);
        var samples = new ArrayList<Sample>();
        for (int i = 0; i < 10; i++) {
            samples.add(new Sample(3 + i, reference.add(axis.scale(2.5 * i)).add(noise(random))));
        }

        var fit = Chains.fitPrismatic(samples);
        assertTrue(fit.joint() instanceof Prismatic);
        assertEquals(3, fit.zero(), 0);
        assertEquals(2.5, fit.perUnit(), 0.001);
        assertEquals(0, fit.joint().axis().subtract(axis).length(), 0.001);
        assertEquals(0, fit.reference().subtract(reference).length(), 0.004);
        assertTrue(fit.residual() < 0.008);
    }

    @Test
    public void rejectsInvalidCoordinatesAxesValuesAndDegenerateSamples() {
        assertThrows(IllegalArgumentException.class, () -> new Vec(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Revolute(ORIGIN, ORIGIN, null));
        assertThrows(IllegalArgumentException.class, () -> Chains.forward(planar(null), List.of(1.0)));
        assertThrows(IllegalArgumentException.class, () -> Chains.forward(planar(null),
            List.of(1.0, Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> Chains.fitRevolute(List.of(
            new Sample(0, ORIGIN), new Sample(1, X), new Sample(2, X.scale(2)))));
        assertThrows(IllegalArgumentException.class, () -> Chains.fitPrismatic(List.of(
            new Sample(1, ORIGIN), new Sample(1, X))));
    }

    private static Chain planar(Range limits) {
        return new Chain(List.of(new Revolute(ORIGIN, Z, limits), new Revolute(X, Z, limits)), X.scale(2));
    }

    private static Vec noise(Random random) {
        return new Vec(random.nextDouble(-0.004, 0.004), random.nextDouble(-0.004, 0.004),
            random.nextDouble(-0.004, 0.004));
    }
}
