package io.github.izakyl.folkways.rts.combat.group;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.combat.group.GroupDeployment;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Deployment intent that the RTS controller can submit without steering entities. */
public record Deployment(WorldPos left, WorldPos right, Vec3 front, Formation formation) {

    public record Formation(double spacing, int columns) {

        public Formation(double spacing) {
            this(spacing, 0);
        }

        public Formation {
            new GroupDeployment.Formation(spacing, columns);
        }
    }

    public Deployment {
        GroupDeployment checked = shared(left, right, front, formation);
        front = checked.front();
    }

    private static GroupDeployment shared(WorldPos left, WorldPos right, Vec3 front, Formation formation) {
        return new GroupDeployment(left, right, front,
            new GroupDeployment.Formation(formation.spacing(), formation.columns()));
    }

    public List<WorldPos> line(int count) {
        return shared(left, right, front, formation).line(count);
    }

    List<WorldPos> column(int count) {
        return shared(left, right, front, formation).column(count);
    }
}
