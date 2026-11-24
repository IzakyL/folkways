package io.github.izakyl.folkways.solve;

import java.util.List;

import io.github.izakyl.folkways.solve.Ballistics.Aim;
import io.github.izakyl.folkways.solve.Ballistics.Model;
import io.github.izakyl.folkways.solve.Ballistics.Point;
import io.github.izakyl.folkways.solve.Ballistics.Shot;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BallisticsTest {

    private static final double ERROR = 1e-7;

    @Test
    public void bothArcsReachTheTargetUnderIndependentTickIntegration() {
        Point target = new Point(-18, 6, 48);
        for (double retention : new double[] {1, .99, .94}) {
            Model model = new Model(5, .12, retention);
            List<Shot> shots = Ballistics.solve(model, target);
            assertEquals(2, shots.size());
            assertTrue(shots.getFirst().aim().pitch() < shots.getLast().aim().pitch());
            for (Shot shot : shots) {
                assertPoint(target, integrate(model, shot.aim(), shot.ticks()));
                assertPoint(target, Ballistics.position(model, shot.aim(), shot.ticks()));
            }
        }
    }

    @Test
    public void landingInterpolatesTheDescendingTick() {
        Model model = new Model(5, .12, .97);
        Aim aim = new Aim(38, 27);
        var landing = Ballistics.land(model, aim, -7).orElseThrow();
        assertEquals(-7, landing.point().y(), 0);
        assertPoint(landing.point(), integrate(model, aim, landing.ticks()));
        assertTrue(Ballistics.position(model, aim, landing.ticks() - .01).y() > -7);
        assertTrue(Ballistics.position(model, aim, landing.ticks() + .01).y() < -7);
    }

    @Test
    public void unreachableAndFlightHorizon() {
        assertTrue(Ballistics.solve(new Model(5, .12, .9), new Point(0, 0, 51)).isEmpty());
        assertTrue(Ballistics.solve(new Model(5, .12, 1), new Point(0, 200, 0)).isEmpty());
        assertTrue(Ballistics.solve(new Model(1, 0, 1), new Point(0, 0, 1201)).isEmpty());
        assertEquals(1200, Ballistics.solve(new Model(1, 0, 1), new Point(0, 0, 1200))
            .getFirst().ticks(), ERROR);
        assertTrue(Ballistics.land(new Model(5, 0, .99), new Aim(0, 30), 0).isEmpty());
    }

    @Test
    public void verticalAndHorizontalShots() {
        Model model = new Model(5, .2, .99);
        assertEquals(0, Ballistics.solve(model, new Point(0, 0, 0)).getFirst().ticks(), 0);
        Point above = new Point(0, 12, 0);
        var up = Ballistics.solve(model, above);
        assertEquals(1, up.size());
        assertEquals(90, up.getFirst().aim().pitch(), ERROR);
        assertPoint(above, integrate(model, up.getFirst().aim(), up.getFirst().ticks()));
        Point below = new Point(0, -12, 0);
        var down = Ballistics.solve(model, below);
        assertEquals(2, down.size());
        assertEquals(-90, down.getFirst().aim().pitch(), ERROR);
        assertEquals(90, down.getLast().aim().pitch(), ERROR);
        for (Shot shot : down) {
            assertPoint(below, integrate(model, shot.aim(), shot.ticks()));
        }

        Point horizontal = new Point(0, 0, 3);
        var flat = Ballistics.solve(model, horizontal).getFirst();
        assertEquals(0, flat.aim().pitch(), ERROR);
        assertPoint(horizontal, integrate(model, flat.aim(), flat.ticks()));
        assertEquals(1, Ballistics.land(model, new Aim(0, 0), 0).orElseThrow().ticks(), ERROR);

        var straight = Ballistics.solve(new Model(5, 0, .99), new Point(0, 0, 100));
        assertEquals(1, straight.size());
        assertEquals(0, straight.getFirst().aim().pitch(), ERROR);
    }

    @Test
    public void detectsTwoCrossingsInOneTick() {
        Model model = new Model(5, 2, 0);
        Point target = new Point(0, -1, 4.99);
        var shots = Ballistics.solve(model, target);
        assertEquals(2, shots.size());
        for (Shot shot : shots) {
            assertEquals(1, (int) shot.ticks());
            assertPoint(target, integrate(model, shot.aim(), shot.ticks()));
        }
    }

    private static Point integrate(Model model, Aim aim, double ticks) {
        double yaw = Math.toRadians(aim.yaw());
        double pitch = Math.toRadians(aim.pitch());
        double vx = -model.speed() * Math.cos(pitch) * Math.sin(yaw);
        double vy = model.speed() * Math.sin(pitch);
        double vz = model.speed() * Math.cos(pitch) * Math.cos(yaw);
        double x = 0;
        double y = 0;
        double z = 0;
        for (int tick = 0; tick < ticks; tick++) {
            double fraction = Math.min(1, ticks - tick);
            x += vx * fraction;
            y += vy * fraction;
            z += vz * fraction;
            vx *= model.retention();
            vy = vy * model.retention() - model.gravity();
            vz *= model.retention();
        }
        return new Point(x, y, z);
    }

    private static void assertPoint(Point expected, Point actual) {
        assertEquals(expected.x(), actual.x(), ERROR);
        assertEquals(expected.y(), actual.y(), ERROR);
        assertEquals(expected.z(), actual.z(), ERROR);
    }
}
