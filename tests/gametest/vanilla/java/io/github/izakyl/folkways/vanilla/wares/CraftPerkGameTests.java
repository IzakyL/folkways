package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.core.effect.Modifier;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.wares.mixin.FurnaceAccessor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class CraftPerkGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CraftPerkGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void vanillaFurnaceTemplatesMayOmitTheWholeTrainingPayload(GameTestHelper helper) {
        var at = helper.absolutePos(new BlockPos(1, 1, 1));
        var state = Blocks.FURNACE.defaultBlockState();
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", "minecraft:furnace");
        var loaded = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
            at, state, tag, helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof AbstractFurnaceBlockEntity,
            "native template NBT must still create a furnace without Folkways training data");
        var saved = loaded.saveWithoutMetadata(helper.getLevel().registryAccess());
        helper.assertTrue(saved.contains("FolkwaysFurnaceBatches", net.minecraft.nbt.Tag.TAG_LIST)
            && saved.contains("FolkwaysFurnaceFraction", net.minecraft.nbt.Tag.TAG_DOUBLE),
            "the current save writer always writes both training fields");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void furnaceCommandNbtMayOmitEitherTrainingField(GameTestHelper helper) {
        for (boolean batches : List.of(false, true)) {
            var at = helper.absolutePos(new BlockPos(batches ? 2 : 1, 1, 1));
            var tag = new net.minecraft.nbt.CompoundTag();
            if (batches) {
                tag.put("FolkwaysFurnaceBatches", new net.minecraft.nbt.ListTag());
            } else {
                tag.putDouble("FolkwaysFurnaceFraction", 0.25);
            }
            var input = new net.minecraft.commands.arguments.blocks.BlockInput(
                Blocks.FURNACE.defaultBlockState(), java.util.Set.of(), tag);
            helper.assertTrue(input.place(helper.getLevel(), at, 3), "partial command placement succeeds");
            var saved = helper.getLevel().getBlockEntity(at).saveWithoutMetadata(helper.getLevel().registryAccess());
            helper.assertTrue(saved.getList("FolkwaysFurnaceBatches", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty(),
                "missing batches default empty");
            helper.assertValueEqual(saved.getDouble("FolkwaysFurnaceFraction"), batches ? 0.0 : 0.25,
                "missing fraction defaults to zero; a supplied value survives");
        }
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void eligibleRecipeRefundsConsumedUnitsAndAddsItsConfiguredByproduct(GameTestHelper helper) {
        RecipeHolder<CraftingRecipe> sticks = (RecipeHolder<CraftingRecipe>) helper.getLevel().getRecipeManager()
            .byKey(ResourceLocation.withDefaultNamespace("stick")).orElseThrow();
        SimpleContainer pool = new SimpleContainer(new ItemStack(Items.OAK_PLANKS, 2));
        var made = Assembly.grid(sticks, List.copyOf(sticks.value().getIngredients()), 1, 2)
            .runOnce(helper.getLevel(), pool, worker()).orElseThrow();
        helper.assertTrue(pool.isEmpty(), "the recipe still consumes both ingredients normally");
        helper.assertValueEqual(made.stream().filter(stack -> stack.is(Items.OAK_PLANKS))
            .mapToInt(ItemStack::getCount).sum(), 2, "each consumed unit is independently refundable");
        helper.assertValueEqual(made.stream().filter(stack -> stack.is(Items.STICK))
            .mapToInt(ItemStack::getCount).sum(), 5, "four recipe sticks plus configured one-stick byproduct");
        var blocked = new java.util.ArrayList<ItemStack>();
        CraftPerks.reward(helper.getLevel(), ResourceLocation.withDefaultNamespace("iron_block"),
            List.of(new ItemStack(Items.IRON_INGOT, 9)), new SimpleContainer(1), worker(), blocked);
        helper.assertTrue(blocked.isEmpty(), "packing recipes have no policy and never refund or add byproducts");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void furnaceTrainingAcceleratesOnlyTheLoadedBatchAndSurvivesSaving(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        AbstractFurnaceBlockEntity furnace = trainedFurnace(helper, pos);
        // A player subsequently adds a second input, which receives no training.
        furnace.getItem(0).grow(1);
        for (int tick = 0; tick < 80; tick++) { tick(helper, pos, furnace); }
        helper.assertTrue(((FurnaceAccessor) furnace).folkways$cooked() >= 115,
            "the first batch gains actual cooking progress");
        var saved = furnace.saveWithoutMetadata(helper.getLevel().registryAccess());
        furnace.loadWithComponents(saved, helper.getLevel().registryAccess());
        for (int tick = 0; tick < 254; tick++) { tick(helper, pos, furnace); }
        helper.assertValueEqual(furnace.getItem(2).getCount(), 2,
            "one trained batch and one untrained batch finish after roughly 334 total ticks across a reload");
        helper.assertTrue(furnace.getItem(0).isEmpty(), "both inputs consumed exactly once");
        furnace.setItem(2, ItemStack.EMPTY);
        furnace.setItem(0, new ItemStack(Items.RAW_GOLD));
        for (int tick = 0; tick < 50; tick++) { tick(helper, pos, furnace); }
        helper.assertTrue(((FurnaceAccessor) furnace).folkways$cooked() <= 50,
            "new player-loaded input does not inherit completed training");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void removingAndReplacingTheSameInputCannotTransferTraining(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        AbstractFurnaceBlockEntity furnace = trainedFurnace(helper, pos);
        for (int tick = 0; tick < 60; tick++) { tick(helper, pos, furnace); }
        furnace.removeItem(0, 1);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON));
        int progress = ((FurnaceAccessor) furnace).folkways$cooked();
        for (int tick = 0; tick < 20; tick++) { tick(helper, pos, furnace); }
        helper.assertValueEqual(((FurnaceAccessor) furnace).folkways$cooked() - progress, 20,
            "same-item removal and replacement between ticks cancels the removed batch's training");
        helper.succeed();
    }

    private static void tick(GameTestHelper helper, BlockPos pos, AbstractFurnaceBlockEntity furnace) {
        AbstractFurnaceBlockEntity.serverTick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), furnace);
    }

    // A furnace with one raw iron loaded by a trained hand, and coal to burn.
    private static AbstractFurnaceBlockEntity trainedFurnace(GameTestHelper helper, BlockPos pos) {
        helper.getLevel().setBlockAndUpdate(pos, Blocks.FURNACE.defaultBlockState());
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(pos);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON));
        furnace.setItem(1, new ItemStack(Items.COAL));
        ((FurnaceTraining) furnace).folkways$loaded(0, 1, .5);
        return furnace;
    }

    private static Worker worker() {
        Effects effects = Effects.of(List.of(
            new Effects.Part("test", Modifier.add(WaresContent.REFUND, 1)),
            new Effects.Part("test", Modifier.add(WaresContent.BYPRODUCT, 1))));
        return Proxies.of(Worker.class, (proxy, method, args) -> switch (method.getName()) {
            case "pack" -> new SimpleContainer(1);
            case "within", "route" -> List.of();
            case "held" -> ItemStack.EMPTY;
            case "placement" -> Placement.NOWHERE;
            case "effects" -> effects;
            default -> throw new AssertionError(method.getName());
        });
    }
}
