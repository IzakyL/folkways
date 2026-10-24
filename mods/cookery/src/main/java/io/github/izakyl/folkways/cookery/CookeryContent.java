package io.github.izakyl.folkways.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.cookery.seeds.SeedPresence;
import io.github.izakyl.folkways.plugins.compost.SeedSupport;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

public final class CookeryContent {
    public static final ResourceLocation ID = id("cookery");
    public static final ResourceLocation STATION = id("kitchen");
    public static final ResourceLocation STOVE = id("stove");
    public static final ResourceLocation PREPARING = id("preparing_food");
    public static final ResourceLocation CHOPPING = id("chopping_food");
    public static final ResourceLocation COOKING = id("cooking_food");
    public static final ResourceLocation TAKING = id("serving_food");

    private static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.cookery",
        ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "stockpot")).tools(WaresContent.CRAFTING);

    private CookeryContent() {
    }

    public static List<ResourceLocation> stations() { return List.of(STATION, STOVE); }

    public static boolean station(BlockState state) {
        return KitchenStations.find(state).isPresent();
    }

    public static void declare(Declaring event) {
        io.github.izakyl.folkways.plugins.compost.SeedReserves.register(List.of(
            com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems.WILD_RICE_SEED.get(),
            com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems.RICE_SEED.get()));
        io.github.izakyl.folkways.cookery.dining.CookeryDiningTables.register();
        BuiltinKitchenStations.register();
        CookeryDining.register();
        if (net.neoforged.fml.ModList.get().isLoaded("kaleidoscope_tavern")) {
            io.github.izakyl.folkways.cookery.tavern.TavernSupport.register();
        }
        io.github.izakyl.folkways.cookery.racks.KitchenRacks.register();
        io.github.izakyl.folkways.cookery.oil.PlacedOilStore.register();
        io.github.izakyl.folkways.cookery.farming.CookeryCrops.register();
        io.github.izakyl.folkways.cookery.farming.CookerySickle.register();
        io.github.izakyl.folkways.cookery.gear.CookeryGear.register(
            colony -> Fronts.of(colony).settings(ID).flag("wear_hat"),
            colony -> Fronts.of(colony).settings(ID).flag("wear_farmer_armor"),
            (colony, item) -> Fronts.of(colony).settings(ID).items("clothing")
                .filter(items -> Goods.matches(item.getDefaultInstance(), items)).isPresent());
        event.urges(ID, io.github.izakyl.folkways.cookery.millstone.MillstoneDrive::urges);
        io.github.izakyl.folkways.cookery.tools.TaskTools.register();
        io.github.izakyl.folkways.core.api.work.WorkDecorators.register(ID, (colony, work) ->
            colony.service(ID, CookeryPresence.class).map(state -> state.tools().decorate(work)).orElse(work));
        event.colony(SeedPresence.ID, scope -> {
            var support = new SeedSupport(scope.colony(), scope.server());
            var seeds = new SeedPresence(scope.colony(), support::budget, () ->
                scope.colony().residents().stream().anyMatch(resident -> {
                    for (var level : scope.server().getAllLevels()) {
                        var entity = level.getEntity(resident.id());
                        if (entity != null && io.github.izakyl.folkways.core.api.resident.body.Bodies.of(entity)
                                .filter(body -> io.github.izakyl.folkways.cookery.gear.CookeryGear.permitsSeeds(scope.colony(), body)).isPresent()) return true;
                    }
                    return false;
                }));
            scope.share(seeds);
            scope.tick(seeds::tick);
            scope.closed(seeds::closed);
        });
        event.colony(ID, scope -> {
            CookeryPresence state = new CookeryPresence(scope.colony(), scope.server());
            scope.share(state);
            scope.watch(STATION, state);
            scope.watch(STOVE, state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering event) {
        event.facing(ID, colony -> colony.service(ID, CookeryPresence.class)
            .map(state -> (io.github.izakyl.folkways.front.api.Facing) state));
        event.enrollment(ID, new Enrollment(List.of(
            new Delegation(STATION, new Shape.Block(state -> !state.is(net.minecraft.world.level.block.Blocks.COMPOSTER)
                && !state.is(com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks.STOVE.get()) && station(state), KitchenStations::anchor)),
            new Delegation(STOVE, new Shape.Point(state -> state.is(com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks.STOVE.get())),
                ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "stove")),
            new Delegation(SeedPresence.GRASS, new Shape.Volume(io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig.maxFarmCells()),
                new Schema(List.of(new Schema.Setting.Flag(SeedPresence.RESTORE_GRASS,
                    "folkways.settings.restore_grass", ResourceLocation.withDefaultNamespace("bone_meal"), true))),
                ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "wild_rice"))),
            new Schema(List.of(
                new Schema.Setting.Flag("wear_hat", "folkways.settings.cookery.wear_hat",
                    ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "straw_hat"), true),
                new Schema.Setting.Flag("wear_farmer_armor", "folkways.settings.cookery.wear_farmer_armor",
                    ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "farmer_chest_plate"), false),
                new Schema.Setting.Items("clothing", "folkways.settings.cookery.clothing",
                    ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "straw_hat"),
                    List.of(ItemFilter.tag(TagMod.STRAW_HAT.location()),
                        ItemFilter.tag(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "farmer_armor")))))),
            List.of(PAGE)));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }
}
