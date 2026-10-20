package io.github.izakyl.folkways.cookery.stove;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Cadence;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryPresence;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoveSchedulingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(StoveSchedulingGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void theRealPresenceKeepsIgnitionWantedAcrossSweepsBeforeAndAfterTheNativeStoveLights(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Grown original = rig.submitted.getFirst();
        helper.onEachTick(() -> { if (!rig.closed) rig.presence.tick(rig.level.getServer()); });
        helper.startSequence().thenIdle(85).thenExecute(() -> {
            helper.assertTrue(rig.published >= 3, "the actual Presence refreshed its registered stove over multiple forty-tick sweeps");
            rig.kept(helper, original);
            helper.assertTrue(Tools.shared(StoveWorkshops.IGNITION).isPresent(), "ignition is available to the same task-scoped borrowing path as other kitchen tools");
            helper.assertTrue(Commit.run(rig.level, original.nodes().getFirst(), rig) instanceof Outcome.Done, "the real submitted ignition node lights the native stove through core commit");
            helper.assertTrue(rig.level.getBlockState(rig.cell).getValue(StoveBlock.LIT), "the native LIT state supplies real heat");
            helper.assertValueEqual(rig.pack.getItem(0).getDamageValue(), 1, "one actual ignition wears the held flint exactly once");
        }).thenIdle(85).thenExecute(() -> {
            try {
                rig.kept(helper, original);
                helper.assertValueEqual(rig.pack.getItem(0).getDamageValue(), 1, "the lit task stays wanted for its eventual tool return without repeating ignition");
                original.nodes().getFirst().ended(rig.level, Ending.DONE); rig.ended.accept(original.nodes().getFirst().id(), Ending.DONE);
                helper.assertTrue(rig.presence.goals(rig.view).isEmpty(), "actual task completion releases the cached original without creating a second lit-stove task");
            } finally { rig.close(); }
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void aWetPlannedStoveWaitsAcrossTheRealSweepAndResumesWithoutWastingItsIgnition(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Grown original = rig.submitted.getFirst(); Node light = original.nodes().getFirst();
        rig.set(rig.cell.above(), Blocks.WATER.defaultBlockState());
        helper.assertTrue(!light.ready(rig.level), "a planned wet stove does not commit an unusable ignition");
        helper.onEachTick(() -> { if (!rig.closed) rig.presence.tick(rig.level.getServer()); });
        helper.startSequence().thenIdle(85).thenExecute(() -> {
            try {
                rig.kept(helper, original);
                helper.assertTrue(!rig.level.getBlockState(rig.cell).getValue(StoveBlock.LIT), "the real submerged stove remains cold");
                helper.assertValueEqual(rig.pack.getItem(0).getDamageValue(), 0, "waiting through several actual sweeps never spends durability");
                rig.set(rig.cell.above(), Blocks.AIR.defaultBlockState());
                helper.assertTrue(light.ready(rig.level) && Commit.run(rig.level, light, rig) instanceof Outcome.Done, "the same original scheduled task resumes when its real heat source dries");
                helper.assertValueEqual(rig.pack.getItem(0).getDamageValue(), 1, "drying requires one real ignition, not one charge per wet refresh");
                light.ended(rig.level, Ending.DONE); rig.ended.accept(light.id(), Ending.DONE);
            } finally { rig.close(); }
        }).thenSucceed();
    }

    private static final class Rig implements Worker, AutoCloseable {
        final ServerLevel level;
        final UUID id = UUID.randomUUID();
        final BlockPos cell;
        final WorldPos at;
        final ResidentEntity actor;
        final SimpleContainer pack = new SimpleContainer(6);
        final Colony colony;
        final ColonyView view;
        final CookeryPresence presence;
        final List<Grown> submitted = new ArrayList<>();
        final List<UUID> withdrawn = new ArrayList<>();
        final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        final Map<ResourceLocation, CompoundTag> kept = new LinkedHashMap<>();
        BiConsumer<UUID, Ending> ended;
        int published;
        boolean closed;

        @SuppressWarnings("unchecked")
        Rig(GameTestHelper helper) {
            level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(2, 25, 2)); at = WorldPos.of(level, cell);
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                set(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                set(cell.offset(x, 0, z), Blocks.AIR.defaultBlockState()); set(cell.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            }
            set(cell, ModBlocks.STOVE.get().defaultBlockState());
            actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(cell.south(2))); actor.setNoAi(true); level.addFreshEntity(actor);
            pack.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            Holding holding = new Holding(UUID.randomUUID(), CookeryContent.STATION, new Held.Block(at));
            view = (ColonyView) Proxy.newProxyInstance(ColonyView.class.getClassLoader(), new Class<?>[] { ColonyView.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "level" -> level;
                    case "dimension" -> level.dimension();
                    case "holdings" -> List.of(holding);
                    case "blocks" -> CookeryContent.STATION.equals(args[0]) ? Set.of(cell) : Set.of();
                    case "residents" -> List.of();
                    case "effectsOf" -> io.github.izakyl.folkways.effect.Effects.NONE;
                    default -> throw new AssertionError(method.getName());
                });
            colony = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[] { Colony.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> id;
                    case "views" -> List.of(view);
                    case "view" -> view;
                    case "holdings" -> args == null || args.length == 0 || CookeryContent.STATION.equals(args[0]) ? List.of(holding) : List.of();
                    case "storesIn", "residents", "reservedInputs" -> List.of();
                    case "service", "store" -> Optional.empty();
                    case "publish" -> { published++; yield null; }
                    case "rule" -> null;
                    case "kept" -> kept.getOrDefault(args[0], new CompoundTag()).copy();
                    case "keep" -> { kept.put((ResourceLocation) args[0], ((CompoundTag) args[1]).copy()); yield null; }
                    case "submit" -> {
                        Grown grown = (Grown) args[2]; submitted.add(grown); ended = (BiConsumer<UUID, Ending>) args[3];
                        helper.assertTrue(grown.nodes().getFirst().planned(level).isEmpty(), "the actual ignition submitted by Asking books its own stove"); yield null;
                    }
                    case "withdraw" -> { withdrawn.add((UUID) args[1]); yield null; }
                    default -> throw new AssertionError(method.getName());
                });
            presence = new CookeryPresence(colony, level.getServer()); presence.held(holding); presence.tick(level.getServer());
            helper.assertValueEqual(submitted.size(), 1, "the actual registered stove produced its ignition root through Presence and Asking");
        }
        void kept(GameTestHelper helper, Grown original) {
            helper.assertValueEqual(submitted.size(), 1, "the same ignition was submitted once");
            helper.assertTrue(withdrawn.isEmpty(), "the real Asking did not revoke the booked ignition as replaced");
            helper.assertTrue(presence.goals(view).size() == 1 && presence.goals(view).getFirst() == original, "the exact original Grown remains wanted across the real sweep");
        }
        void set(BlockPos pos, BlockState state) { before.putIfAbsent(pos.immutable(), level.getBlockState(pos)); level.setBlockAndUpdate(pos, state); }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) {
            for (int slot = 0; slot < pack.getContainerSize(); slot++) if (Tools.answers(pack.getItem(slot), need)) return pack.getItem(slot);
            return ItemStack.EMPTY;
        }
        public void spill(ItemStack stack) { throw new AssertionError("unexpected spill: " + stack); }
        public io.github.izakyl.folkways.effect.Effects effects() { return actor.effects(); }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() {
            closed = true; presence.closed(Closing.SHUTDOWN); Cadence.forget(colony); actor.discard(); before.forEach(level::setBlockAndUpdate);
        }
    }
}