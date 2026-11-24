package io.github.izakyl.folkways.solve;

import io.github.izakyl.folkways.solve.Kinematics.Axis;
import io.github.izakyl.folkways.solve.Kinematics.Range;
import io.github.izakyl.folkways.solve.Kinematics.Target;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class KinematicsTest {

    @Test
    public void clampsEachAxisAndIncludesEndpoints() {
        Range x = new Range(-3, 5);
        Range y = new Range(-8, 0);
        Range z = new Range(2, 9);
        assertEquals(new Target(-3, 0, 9, true), Kinematics.solve(x, y, z, -3, 0, 9));
        assertEquals(new Target(-3, -8, 9, false), Kinematics.solve(x, y, z, -7, -10, 100));
        assertEquals(new Target(5, 0, 2, false), Kinematics.solve(x, y, z, 7, 10, -100));
        assertEquals(new Target(1, -4, 6, true), Kinematics.solve(x, y, z, 1, -4, 6));
    }

    @Test
    public void convertsCoordinatesToAxisTravel() {
        Axis x = new Axis(12, 2, new Range(0, 4));
        Axis y = new Axis(20, -1, new Range(0, 10));
        Axis z = new Axis(-6, -0.5, new Range(0, 8));

        assertEquals(new Target(2, 6, 4, true), Kinematics.solve(x, y, z, 16, 14, -8));
        assertEquals(new Target(4, 0, 8, true), Kinematics.solve(x, y, z, 20, 20, -10));
        assertEquals(new Target(0, 10, 8, false), Kinematics.solve(x, y, z, 11, 8, -11));
        assertEquals(14, y.coordinate(6), 0);
    }
}
