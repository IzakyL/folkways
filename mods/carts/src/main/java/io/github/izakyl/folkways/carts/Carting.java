package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Locomotion;
import io.github.izakyl.folkways.core.resident.api.MobControls;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.resident.api.Search;
import io.github.izakyl.folkways.plugins.person.walk.OnFoot;
import io.github.izakyl.folkways.plugins.person.walk.ResidentMoveControl;
import io.github.izakyl.folkways.plugins.person.walk.ResidentNodeEvaluator;
import io.github.izakyl.folkways.plugins.person.walk.ResidentPathNavigation;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.phys.AABB;

public final class Carting implements Locomotion {
    public static final Carting INSTANCE = new Carting();
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "carting");

    private Carting() {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public NodeEvaluator newEvaluator(Search search) {
        return new CartNodeEvaluator(search.from().orElse(null));
    }

    @Override
    public void fit(Mob body) {
        if (!(body.getNavigation() instanceof Navigation)) {
            MobControls.navigation(body, new Navigation(body, body.level()));
            MobControls.moveControl(body, new ResidentMoveControl(body) {
                @Override
                protected double walkingWidth() {
                    // Cart clearance is centred on the path, including through corners.
                    return 1.3D;
                }
            });
        }
    }

    @Override
    public Conveyance along(double speed, RefusalKind lost) {
        return new OnFoot(speed, lost);
    }

    private static final class Navigation extends ResidentPathNavigation {
        private Navigation(Mob mob, Level level) {
            super(mob, level);
        }

        @Override
        protected List<AABB> obstacles(double range) {
            var own = CartLinks.pulling(mob).map(AbstractCargoCart::getBoundingBox);
            return super.obstacles(range).stream().filter(box -> own.filter(box::equals).isEmpty()).toList();
        }

        @Override
        protected ResidentNodeEvaluator evaluator() {
            return new CartNodeEvaluator(null);
        }
    }
}
