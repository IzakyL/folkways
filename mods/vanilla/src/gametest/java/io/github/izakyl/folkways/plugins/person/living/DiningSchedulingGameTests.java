package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.WorkExertion;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.labor.ColonyLabor;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.Container;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiningSchedulingGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(DiningSchedulingGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 1400)
    public static void realSchedulerSetsVanillaTableThenCompletesMealAndReleasesPose(GameTestHelper helper) {
        var rig = new Rig(helper, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
            Blocks.OAK_STAIRS.defaultBlockState(), new ItemStack(Items.CAKE));
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(rig.level.getBlockState(rig.table.above()).is(Blocks.CAKE),
                "the scheduler must fetch the cake from real stock and place it on the marked table"))
            .thenExecute(() -> {
                helper.assertTrue(rig.setting, "the real executor performed setting_table work");
                helper.assertValueEqual(rig.sourceCount(), 0, "the one stocked cake was spent by real planned execution");
                helper.assertValueEqual(rig.level.getBlockState(rig.table.above()).getValue(CakeBlock.BITES), 0,
                    "the delivered cake starts whole");
                rig.hungry();
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.living.mood(rig.person.getUUID()).dined >= rig.hungryAt,
                "the scheduled dining urge must finish its full windup and commit"))
            .thenExecute(() -> {
                helper.assertTrue(rig.dining, "the executor ran the dining gesture during the meal");
                helper.assertTrue(rig.living.foodLevelOf(rig.person.getUUID()).orElseThrow() > rig.foodBefore,
                    "a real meal increased the resident's hunger state");
                helper.assertValueEqual(rig.level.getBlockState(rig.table.above()).getValue(CakeBlock.BITES), 1,
                    "one scheduled meal consumes exactly one native cake bite");
                helper.assertTrue(rig.person.leisureMotion().isEmpty(), "completed dining releases the eating pose");
                helper.assertFalse(rig.person.isPassenger(), "the finished diner is free to work again");
                rig.close();
            })
            .thenSucceed();
    }

    /** Shared native fixture. GameTestServer suppresses Labor.work, so this drives the complete
     * per-colony planning/execution pipeline once per real tick, using its normal inline lanes. */
    public static final class Rig implements AutoCloseable {
        public final GameTestHelper helper;
        public final ServerLevel level;
        public final Colony colony;
        public final LivingPresence living;
        public final ResidentEntity person;
        public final BlockPos table;
        public final BlockPos chair;
        public final Container source;
        public final int startingStock;
        private final net.minecraft.world.item.Item foodItem;
        public boolean setting;
        public boolean dining;
        public int mountedTicks;
        public long hungryAt = Long.MAX_VALUE;
        public int foodBefore;
        private final Set<Long> forced = new java.util.HashSet<>();
        private final ColonyData data;
        private final ColonyLabor labor;
        private final Object inline;
        private final java.lang.reflect.Method run;
        private final java.lang.reflect.Method census;
        private final java.lang.reflect.Method stop;
        private final java.lang.reflect.Field diagnosis;
        private boolean closed;

        public Rig(GameTestHelper helper, BlockState tableBlock, BlockState chairBlock, ItemStack food) {
            this.helper = helper;
            level = helper.getLevel();
            table = helper.absolutePos(new BlockPos(4, 2, 4));
            chair = table.south();
            Set<Long> alreadyForced = Set.copyOf(level.getForcedChunks());
            for (int cx = (table.getX() - 4) >> 4; cx <= (table.getX() + 4) >> 4; cx++) {
                for (int cz = (table.getZ() - 4) >> 4; cz <= (table.getZ() + 4) >> 4; cz++) {
                    long chunk = ChunkPos.asLong(cx, cz);
                    if (!alreadyForced.contains(chunk) && level.setChunkForced(cx, cz, true)) forced.add(chunk);
                }
            }
            for (BlockPos floor : BlockPos.betweenClosed(table.offset(-3, -1, -3), table.offset(3, -1, 3))) {
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(floor.above(y), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(table, tableBlock);
            level.setBlockAndUpdate(chair, chairBlock);
            BlockPos chest = table.offset(2, 0, -1);
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            source = (Container) level.getBlockEntity(chest);
            foodItem = food.getItem();
            startingStock = 1;
            for (int slot = 0; slot < startingStock; slot++) source.setItem(slot, food.copyWithCount(1));
            source.setChanged();
            colony = Colonies.mint(level.getServer());
            colony.stores(ResourceLocation.fromNamespaceAndPath("folkways", "dining_test_stock"),
                level.dimension(), List.of(WorldPos.of(level, chest)));
            data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            labor = new ColonyLabor(data, level.dimension());
            try {
                Class<?> lanes = Class.forName("io.github.izakyl.folkways.core.engine.labor.Lanes");
                var field = lanes.getDeclaredField("INLINE");
                field.setAccessible(true);
                inline = field.get(null);
                run = ColonyLabor.class.getMethod("run", ServerLevel.class, lanes);
                census = ColonyLabor.class.getDeclaredMethod("census", ServerLevel.class);
                census.setAccessible(true);
                stop = ColonyLabor.class.getDeclaredMethod("close");
                stop.setAccessible(true);
                diagnosis = ColonyLabor.class.getDeclaredField("diagnosis");
                diagnosis.setAccessible(true);
            } catch (ReflectiveOperationException broken) { throw new IllegalStateException(broken); }
            // Use the normal independent food-setting scopes: bread is pocket ration, while the dining
            // zone selects cake or soup. Its sole table serving is therefore available in real stock.
            ColonyFront.of(colony).setSetting(LivingContent.ID, LivingContent.FOOD.key(),
                new ColonySettings.Value.Items(List.of(ItemFilter.item(BuiltInRegistries.ITEM.getKey(Items.BREAD)))));
            ColonySettings settings = ColonySettings.empty()
                .with(LivingContent.DINING_SERVINGS.key(), new ColonySettings.Value.Count(1))
                .with(LivingContent.DINING_FOOD.key(), new ColonySettings.Value.Items(List.of(
                    ItemFilter.item(BuiltInRegistries.ITEM.getKey(food.getItem())))));
            ColonyFront.of(colony).addZone(level, LivingContent.DINING, Set.of(table, chair), settings).orElseThrow();
            person = PersonBody.RESIDENT.get().create(level);
            person.moveTo(Vec3.atBottomCenterOf(table.offset(2, 0, 1)));
            colony.hold(PersonContent.ID, Held.Entity.of(person)).orElseThrow();
            person.joinColony(colony.id());
            level.addFreshEntity(person);
            colony.entered(person);
            living = colony.service(LivingContent.ID, LivingPresence.class).orElseThrow();
            helper.onEachTick(() -> {
                if (closed) return;
                ColonyWays.advance(data, level);
                try { run.invoke(labor, level, inline); }
                catch (ReflectiveOperationException broken) { throw new IllegalStateException("dining pipeline failed: " + status(), broken); }
                setting |= person.doing().filter(doing -> doing.what().equals(LivingContent.SETTING_TABLE)).isPresent();
                dining |= person.leisureMotion().equals("dining");
                if (person.isPassenger()) mountedTicks++;
            });
            helper.runAtTickTime(1380L, () -> {
                if (!closed) org.slf4j.LoggerFactory.getLogger("folkways-dining-test").warn("Dining scheduling progress: {}", status());
            });
            helper.runAtTickTime(1390, this::close);
        }

        public void hungry() {
            hungryAt = level.getGameTime();
            // Drive the live colony hunger service through its public event, leaving the scheduler and
            // resident's inventory untouched. Zero active ticks avoids manufacturing a sleep request.
            double appetite = person.effects().value(io.github.izakyl.folkways.effect.Stats.APPETITE);
            WorkExertion.performed(person, 1680 / (appetite > 0 ? appetite : 1), 0, 0);
            foodBefore = living.foodLevelOf(person.getUUID()).orElseThrow();
            helper.assertTrue(foodBefore <= 14, "real work exhaustion makes this resident want a meal");
        }

        public int sourceCount() {
            int count = 0;
            for (int slot = 0; slot < source.getContainerSize(); slot++) {
                ItemStack stack = source.getItem(slot);
                if (stack.is(foodItem)) count += stack.getCount();
            }
            return count;
        }

        public String status() {
            try {
                return "colony=" + colony.id() + " bodyTicks=" + person.tickCount + " pos=" + person.position()
                    + " ticking=" + level.isPositionEntityTicking(person.blockPosition()) + " table=" + table
                    + " doing=" + person.doing() + " balk=" + person.balk() + " mountedTicks=" + mountedTicks
                    + " sourceCount=" + sourceCount() + " inventory="
                    + java.util.stream.IntStream.range(0, person.pack().getContainerSize())
                        .mapToObj(person.pack()::getItem).filter(stack -> !stack.isEmpty()).toList() + " census=" + census.invoke(labor, level)
                    + " diagnosis=" + diagnosis.get(labor);
            } catch (ReflectiveOperationException broken) { return broken.toString(); }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try { stop.invoke(labor); }
            catch (ReflectiveOperationException broken) { throw new IllegalStateException(broken); }
            person.discard();
            colony.raze();
            forced.forEach(chunk -> level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false));
        }
    }
}
