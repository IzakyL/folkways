package io.github.izakyl.folkways.core.api.resident;

import java.util.Objects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

public final class MobControls {

    public interface Fitting {

        void navigation(Mob body, PathNavigation navigation);

        void moveControl(Mob body, MoveControl control);
    }

    private static volatile Fitting fitting;

    private MobControls() {
    }

    public static void install(Fitting how) {
        fitting = Objects.requireNonNull(how, "how");
    }

    public static void navigation(Mob body, PathNavigation navigation) {
        required().navigation(body, navigation);
    }

    public static void moveControl(Mob body, MoveControl control) {
        required().moveControl(body, control);
    }

    private static Fitting required() {
        Fitting how = fitting;
        if (how == null) {
            throw new IllegalStateException("nothing installed a way to fit a body with its own navigation");
        }
        return how;
    }
}
