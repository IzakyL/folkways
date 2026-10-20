package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.effect.Effects;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
public final class CookeryFurnaceGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryFurnaceGameTests.class); }

    @GameTestGenerator
    public static Collection<TestFunction> nativeCookingResources() {
        List<ResourceLocation> ids = new ArrayList<>();
        try (var stream = CookeryFurnaceGameTests.class.getResourceAsStream("/cookery-native-recipes.json")) {
            if (stream == null) throw new IllegalStateException("missing pinned recipe manifest");
            JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonArray("recipes").forEach(value -> {
                    String id = value.getAsString();
                    if (id.endsWith("_from_smelting") || id.endsWith("_from_smoking")) ids.add(ResourceLocation.parse(id));
                });
        } catch (java.io.IOException broken) { throw new IllegalStateException(broken); }
        if (ids.size() != 13) throw new IllegalStateException("expected seven smelting and six smoking recipes, got " + ids.size());
        return ids.stream().map(id -> new TestFunction("cookery_furnace_matrix", "folkways:matrix_furnace_" + id.getPath(),
            "folkways:empty", 700, 0L, true, helper -> run(helper, id))).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void run(GameTestHelper helper, ResourceLocation id) {
        var level = helper.getLevel();
        RecipeHolder<?> holder = level.getRecipeManager().byKey(id).orElseThrow();
        AbstractCookingRecipe recipe = (AbstractCookingRecipe) holder.value();
        ItemStack input = recipe.getIngredients().getFirst().getItems()[0].copyWithCount(2);
        ItemStack output = recipe.getResultItem(level.registryAccess()).copyWithCount(2);
        helper.assertTrue(level.getRecipeManager().getRecipeFor((RecipeType) recipe.getType(), new SingleRecipeInput(input.copyWithCount(1)), level)
            .filter(selected -> ((RecipeHolder<?>) selected).id().equals(id)).isPresent(), id + ": native matching selects this exact owned resource");
        Rig rig = new Rig(helper, input);
        Block block = recipe.getType() == RecipeType.SMOKING ? Blocks.SMOKER : Blocks.FURNACE;
        level.setBlockAndUpdate(rig.cell, block.defaultBlockState());
        RecipeType<? extends AbstractCookingRecipe> type = block == Blocks.SMOKER ? RecipeType.SMOKING : RecipeType.SMELTING;
        var host = new CookHost(rig.at, block, type, rig.stances, new Bookings(), ignored -> Set.of(rig.at), () -> {});
        var bench = host.frozen(RecipeIndex.over(level, level.getRecipeManager()), level, Optional.of(Goods.specOf(Items.COAL.getDefaultInstance())));
        var intent = Produce.of(WaresContent.ID, new WorkSite.AtBlock(rig.at), Goods.specOf(output), 2, Produce.UNBOUNDED);
        Smelt selected = bench.options(intent, Grown.of(intent)).stream().map(change -> change.replacement().nodes().getFirst())
            .filter(node -> node instanceof Smelt smelt && Goods.matches(input, smelt.takes()) && smelt.load() == 2)
            .map(node -> (Smelt) node).findFirst().orElseThrow();
        Grown work = Smelt.SMELTING.expand(selected).orElseThrow();
        Node take = work.nodes().stream().filter(node -> node instanceof TakeCookedNode).findFirst().orElseThrow();
        work.nodes().stream().filter(node -> node != take).forEach(node -> helper.assertTrue(Commit.run(level, node, rig) instanceof Outcome.Done,
            id + ": real input and real fuel load through colony work"));
        long started = level.getGameTime();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(take.ready(level), id + ": actual native cooker output becomes available"))
            .thenExecute(() -> {
                helper.assertTrue(level.getGameTime() - started >= recipe.getCookingTime(), id + ": native cooking time is not skipped");
                // Two physical portions, potentially finishing on separate native ticks.
            }).thenWaitUntil(() -> helper.assertValueEqual(((Container) level.getBlockEntity(rig.cell)).getItem(Machines.RESULT_SLOT).getCount(), 2,
                id + ": both physical portions finish before taking"))
            .thenExecute(() -> {
                try {
                    helper.assertTrue(level.getGameTime() - started >= (long) recipe.getCookingTime() * 2,
                        id + ": both native cooking cycles elapsed");
                    Outcome settled = Commit.run(level, take, rig);
                    helper.assertTrue(settled instanceof Outcome.Done, id + ": actual served output settles");
                    ((Outcome.Done) settled).made().forEach(stack -> helper.assertTrue(Containers.insert(rig.pack, stack).isEmpty(), "actual output fits"));
                    helper.assertValueEqual(Goods.countIn(rig.pack, Goods.specOf(output)), 2L, id + ": exactly two native foods are delivered");
                    helper.assertValueEqual(Goods.countIn(rig.pack, Goods.specOf(input)), 0L, id + ": two real raw inputs were spent");
                    Container furnace = (Container) level.getBlockEntity(rig.cell);
                    helper.assertTrue(furnace.isEmpty(), id + ": native slots contain no duplicated ingredient, fuel or product");
                    helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(rig.cell).inflate(3)).isEmpty(), id + ": no copied loose output");
                } finally { rig.actor.discard(); }
            }).thenSucceed();
    }

    private static final class Rig implements Worker {
        final ServerLevel level;
        final BlockPos cell;
        final WorldPos at;
        final Stances stances;
        final ResidentEntity actor;
        final SimpleContainer pack;
        Rig(GameTestHelper helper, ItemStack input) {
            level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(1, 1, 1)); at = WorldPos.of(level, cell);
            level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(cell.east())); level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition()); pack = new SimpleContainer(input.copy(), new ItemStack(Items.COAL), ItemStack.EMPTY);
        }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new IllegalStateException("unexpected spill " + stack); }
        public Effects effects() { return actor.effects(); }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
    }
}
