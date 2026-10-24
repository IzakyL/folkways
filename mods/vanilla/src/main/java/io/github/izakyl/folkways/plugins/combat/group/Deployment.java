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

    public record Formation(double spacing, int columns) {
        public Formation(double spacing) { this(spacing, 0); }
        public Formation {
            if (!Double.isFinite(spacing) || spacing < 2 || spacing > 8 || columns < 0) {
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
        if (count < 1) { throw new IllegalArgumentException("groups require members"); }
        Vec3 a = Vec3.atBottomCenterOf(left.cell());
        Vec3 b = Vec3.atBottomCenterOf(right.cell());
        int columns = formation.columns() == 0 ? count : Math.min(count, formation.columns());
        if (a.distanceTo(b) + .001 < (columns - 1) * formation.spacing()) {
            throw new IllegalArgumentException("the deployment line is too short for its members");
        }
        List<WorldPos> cells = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            int row = n / columns;
            int inRow = Math.min(columns, count - row * columns);
            double fraction = columns == 1 ? .5 : (n % columns + (columns - inRow) / 2.0) / (columns - 1);
            cells.add(left.at(BlockPos.containing(a.lerp(b, fraction).subtract(front.scale(row * formation.spacing())))));
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
