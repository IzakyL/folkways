package io.github.izakyl.folkways.plugins.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

public final class CookeryContent {
    public static final ResourceLocation ID = id("cookery");
    public static final ResourceLocation STATION = id("kitchen");
    public static final ResourceLocation PREPARING = id("preparing_food");
    public static final ResourceLocation CHOPPING = id("chopping_food");
    public static final ResourceLocation COOKING = id("cooking_food");
    public static final ResourceLocation TAKING = id("serving_food");

    private static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.cookery",
        ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "stockpot"));

    private CookeryContent() {
    }

    public static boolean station(BlockState state) {
        return state.is(ModBlocks.CHOPPING_BOARD.get()) || state.is(ModBlocks.STOCKPOT.get());
    }

    public static void declare(Declaring event) {
        event.kit(WaresContent.CRAFTING, ItemSpec.of(TagMod.KITCHEN_KNIFE));
        event.colony(ID, scope -> {
            CookeryPresence state = new CookeryPresence(scope.colony(), scope.server());
            scope.share(state);
            scope.watch(STATION, state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering event) {
        event.facing(ID, colony -> colony.service(ID, CookeryPresence.class)
            .map(state -> (io.github.izakyl.folkways.front.api.Facing) state));
        event.enrollment(ID, new Enrollment(List.of(new Delegation(STATION, new Shape.Block(CookeryContent::station))),
            Schema.none(), List.of(PAGE)));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }
}
