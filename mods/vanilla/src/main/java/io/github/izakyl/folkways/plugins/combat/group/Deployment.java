package io.github.izakyl.folkways.plugins.combat.group;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Vanilla deployment intent that a future War controller can submit without steering entities. */
public record Deployment(WorldPos left, WorldPos right, Vec3 front, Formation formation) {

    public record Formation(double spacing) {
        public Formation {
            if (!Double.isFinite(spacing) || spacing < 2 || spacing > 8) {
                throw new IllegalArgumentException("formation spacing must be between two and eight blocks");
            }
        }
    }

    public Deployment {
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        Objects.requireNonNull(front);
        Objects.requireNonNull(formation);
        if (!left.sameRealm(right) || left.cell().getY() != right.cell().getY()
                || !Double.isFinite(front.lengthSqr()) || front.horizontalDistanceSqr() < .01 || Math.abs(front.y) > .001) {
            throw new IllegalArgumentException("deployment needs level endpoints in one realm and a horizontal front");
        }
        front = front.normalize();
        Vec3 along = Vec3.atLowerCornerOf(right.cell()).subtract(Vec3.atLowerCornerOf(left.cell()));
        if (along.length() > 128 || along.lengthSqr() > 0 && Math.abs(along.normalize().dot(front)) > .1) {
            throw new IllegalArgumentException("the line must be perpendicular to its front and at most 128 blocks long");
        }
    }

    public List<WorldPos> line(int count) {
        if (count < 1 || count > 8) { throw new IllegalArgumentException("groups have one to eight members"); }
        Vec3 a = Vec3.atBottomCenterOf(left.cell());
        Vec3 b = Vec3.atBottomCenterOf(right.cell());
        if (a.distanceTo(b) + .001 < (count - 1) * formation.spacing()) {
            throw new IllegalArgumentException("the deployment line is too short for its members");
        }
        List<WorldPos> cells = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            double fraction = count == 1 ? .5 : n / (double) (count - 1);
            cells.add(left.at(BlockPos.containing(a.lerp(b, fraction))));
        }
        if (new HashSet<>(cells).size() != cells.size()) { throw new IllegalArgumentException("formation cells overlap"); }
        return List.copyOf(cells);
    }

    List<WorldPos> column(int count) {
        Vec3 center = Vec3.atBottomCenterOf(left.cell()).lerp(Vec3.atBottomCenterOf(right.cell()), .5);
        List<WorldPos> cells = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            cells.add(left.at(BlockPos.containing(center.subtract(front.scale(n * formation.spacing())))));
        }
        return List.copyOf(cells);
    }
}
