package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.front.api.CoreSettings;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PatrolSupplyGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(PatrolSupplyGameTests.class); }

    @GameTest(template = "empty_large")
    public static void manualEquipmentAndCarryTargetChangesInvalidateOldSupplyDemands(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        try {
            scene.store.setItem(0, new ItemStack(Items.BOW));
            scene.store.setItem(1, new ItemStack(Items.ARROW, 32));
            scene.supplies.loadout(scene.body, PatrolSupplies.Loadout.BOW);
            scene.guard();
            Worker worker = worker(scene.body);
            var order = scene.commands.current(scene.body.id());
            Node oldWeapon = scene.supplies.urges(worker, order, Long.MIN_VALUE, Long.MIN_VALUE).getFirst().take().get();
            helper.assertTrue(oldWeapon.ready(scene.level), "the original automatic weapon request is initially live");
            ItemStack bow = new ItemStack(Items.BOW);
            io.github.izakyl.folkways.core.api.resident.body.PersonalItems.mark(scene.body, bow, "mainhand");
            scene.body.pack().insert(bow);
            io.github.izakyl.folkways.core.api.resident.body.Equipment.lock(scene.body, EquipmentSlot.MAINHAND, true);
            helper.assertFalse(oldWeapon.ready(scene.level), "manual assignment invalidates the obsolete automatic weapon collection");
            Node eight = scene.supplies.urges(worker, order, Long.MIN_VALUE, Long.MIN_VALUE).getFirst().take().get();
            helper.assertValueEqual(eight.spec().needs().getFirst().count(), 8L, "the new request only asks for the current ammunition goal");
            var settings = new net.minecraft.nbt.CompoundTag(); settings.putInt("ammoTarget", 32);
            scene.body.getPersistentData().put("folkways:resident_equipment", settings);
            helper.assertFalse(eight.ready(scene.level), "raising the goal invalidates the captured eight-arrow request");
            Node thirtyTwo = scene.supplies.urges(worker, order, Long.MIN_VALUE, Long.MIN_VALUE).getFirst().take().get();
            helper.assertValueEqual(thirtyTwo.spec().needs().getFirst().count(), 32L, "the new request uses the actual updated goal");
            settings.putInt("ammoTarget", 8);
            helper.assertFalse(thirtyTwo.ready(scene.level), "a lower target prevents the old trip from collecting excess ammunition");
            helper.assertValueEqual(scene.countInStore(Items.BOW), 1, "changing equipment never consumes the unneeded warehouse bow");
            helper.assertValueEqual(scene.countInStore(Items.ARROW), 32, "changing goals does not debit stock early");
        } finally { scene.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 550)
    public static void optionalWeaponsRespectAnExplicitToolBanWithoutBecomingEveryResidentsKit(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ColonyFront front = ColonyFront.of(scene.colony);
        List<ItemFilter> original = front.settings(CoreSettings.CORE).entries(CoreSettings.TOOL_KEY);
        ItemFilter bow = ItemFilter.item(ResourceLocation.withDefaultNamespace("bow"));
        ItemFilter crossbow = ItemFilter.item(ResourceLocation.withDefaultNamespace("crossbow"));
        helper.assertTrue(KitRules.allows(scene.colony, Items.BOW) && KitRules.allows(scene.colony, Items.CROSSBOW),
            "fresh colonies allow the declared optional weapons");
        helper.assertValueEqual(CoreSettings.tool().byDefault().stream().filter(bow::equals).count(), 1L,
            "the default list names the optional bow once");
        helper.assertTrue(CoreSettings.tool().byDefault().contains(crossbow), "the optional crossbow is permitted by default");
        boolean lateDeclarationRefused = false;
        try { CoreSettings.defaultTool(io.github.izakyl.folkways.core.api.terms.ItemSpec.of(bow.id())); }
        catch (IllegalStateException expected) { lateDeclarationRefused = true; }
        helper.assertTrue(lateDeclarationRefused, "runtime callers cannot change declared default permissions");
        helper.assertTrue(Vocations.required(PatrolContent.VOCATION).kit().stream()
                .noneMatch(tool -> Goods.matches(new ItemStack(Items.BOW), tool) || Goods.matches(new ItemStack(Items.CROSSBOW), tool)),
            "optional permissions do not create automatic vocation equipment requests");
        List<ItemFilter> forbidden = original.stream().filter(item -> !item.equals(bow)).toList();
        helper.assertTrue(front.setSetting(CoreSettings.CORE, CoreSettings.TOOL_KEY, new ColonySettings.Value.Items(forbidden)),
            "the player explicitly removes the bow permission");
        scene.store.setItem(0, new ItemStack(Items.BOW)); scene.store.setItem(1, new ItemStack(Items.ARROW, 8));
        scene.supplies.loadout(scene.body, PatrolSupplies.Loadout.BOW); scene.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(scene.supplyState() == PatrolSupplies.State.NO_STOCK,
                "the explicit tool ban prevents collection" + scene.debug()))
            .thenIdle(25)
            .thenExecute(() -> {
                helper.assertValueEqual(scene.countInStore(Items.BOW), 1, "a loadout cannot override the player's tool ban");
                helper.assertValueEqual(scene.body.pack().count(Items.BOW), 0, "forbidden equipment never reaches the resident");
                helper.assertValueEqual(front.settings(CoreSettings.CORE).entries(CoreSettings.TOOL_KEY), forbidden,
                    "default permissions never migrate over an explicit player setting");
                helper.assertTrue(front.setSetting(CoreSettings.CORE, CoreSettings.TOOL_KEY, new ColonySettings.Value.Items(original)),
                    "the player allows the bow again");
            })
            .thenWaitUntil(() -> helper.assertTrue(scene.body.pack().count(Items.BOW) == 1 && scene.body.pack().count(Items.ARROW) == 8,
                "the same guard collects actual equipment after permission returns" + scene.debug()))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.countInStore(Items.BOW), 0, "the permitted real bow moves once");
                helper.assertValueEqual(scene.countInStore(Items.ARROW), 0, "the permitted finite ammunition moves once");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 650)
    public static void aGuardWalksToRealStorageForAWeaponAndArrowsThenShoots(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.setDamageValue(9); bow.set(DataComponents.CUSTOM_NAME, Component.literal("warehouse bow"));
        ItemStack arrows = new ItemStack(Items.ARROW, 16);
        arrows.set(DataComponents.CUSTOM_NAME, Component.literal("warehouse ammunition"));
        scene.store.setItem(0, bow); scene.store.setItem(1, arrows);
        scene.supplies.loadout(scene.body, PatrolSupplies.Loadout.BOW);
        Zombie enemy = scene.enemy(7);
        scene.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(scene.supplyState() == PatrolSupplies.State.FETCHING, "a guard with no equipment starts a finite stock trip" + scene.debug()))
            .thenExecute(() -> helper.assertTrue(scene.store.getItem(0).is(Items.BOW)
                && scene.body.pack().count(Items.BOW) == 0, "stock remains in storage while the resident is still far away"))
            .thenWaitUntil(() -> helper.assertTrue(enemy.getHealth() < enemy.getMaxHealth(),
                "the guard obtains the real loadout and uses the normal weapon runner to shoot" + scene.debug()))
            .thenExecute(() -> {
                helper.assertTrue(enemy.getLastDamageSource() != null
                    && enemy.getLastDamageSource().getDirectEntity() instanceof AbstractArrow
                    && enemy.getLastDamageSource().getEntity() == scene.body, "the damage comes from the supplied resident's actual arrow");
                helper.assertTrue(scene.body.getLastHurtByMob() == null, "the NoAI enemy never provoked the resident");
                helper.assertValueEqual(scene.countInStore(Items.BOW), 0, "exactly one warehouse bow is taken");
                helper.assertValueEqual(scene.countInStore(Items.ARROW), 8, "the finite request takes only eight actual arrows");
                ItemStack actual = scene.actualWeapon();
                helper.assertValueEqual(actual.get(DataComponents.CUSTOM_NAME), Component.literal("warehouse bow"), "weapon components survive storage handoff");
                helper.assertTrue(actual.getDamageValue() > 9, "native shooting wears the actual warehouse bow");
                helper.assertValueEqual(scene.weaponCount(), 1, "the pack and real hand jointly own one weapon");
                helper.assertTrue(scene.leftAnchor, "the same resident physically walks to collect the stock");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 550)
    public static void aStockoutReleasesTheRunnerAndRetriesAfterRealStockAppears(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.body.pack().insert(new ItemStack(Items.BOW));
        scene.guard(); // Existing ranged equipment infers ammunition without selecting another weapon.
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(scene.supplyState() == PatrolSupplies.State.NO_STOCK,
                "a stockout is publicly observable without a permanent fetch action" + scene.debug()))
            .thenIdle(25)
            .thenExecute(() -> {
                helper.assertTrue(Reach.standingAt(scene.body.position(), scene.anchor.block(scene.level)),
                    "an unavailable supply does not take the guard away indefinitely");
                helper.assertTrue(!scene.body.hasWeaponSession(), "stockout creates no native weapon loan");
                scene.store.setItem(0, new ItemStack(Items.ARROW, 3));
            })
            .thenWaitUntil(() -> helper.assertValueEqual(scene.body.pack().count(Items.ARROW), 3,
                "a bounded retry collects an available partial batch instead of requiring all eight" + scene.debug()))
            .thenWaitUntil(() -> helper.assertTrue(Reach.standingAt(scene.body.position(), scene.anchor.block(scene.level)),
                "after collection the same guard returns to its slot" + scene.debug()))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.countInStore(Items.ARROW), 0, "all three real supplied arrows leave this warehouse");
                helper.assertValueEqual(scene.body.pack().count(Items.BOW), 1, "inferred ammunition replenishment acquires no spare bow");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 450)
    public static void aNewIndividualOrderCancelsTheOldSupplyGeneration(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.store.setItem(0, new ItemStack(Items.BOW)); scene.store.setItem(1, new ItemStack(Items.ARROW, 16));
        scene.supplies.loadout(scene.body, PatrolSupplies.Loadout.BOW);
        scene.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(scene.supplyState() == PatrolSupplies.State.FETCHING && scene.body.getX() > scene.at(3, 1, 3).cell().getX(),
                "the runner starts actually travelling for its guard's supplies" + scene.debug()))
            .thenExecute(() -> scene.commands.hold(scene.body, scene.at(2, 1, 3)))
            .thenIdle(70)
            .thenExecute(() -> {
                helper.assertTrue(scene.commands.current(scene.body.id()).orElseThrow().mode() == ResidentCommands.Mode.HOLD,
                    "the newer individual handle retains control");
                helper.assertValueEqual(scene.body.pack().count(Items.BOW), 0, "a cancelled trip cannot complete its old requisition");
                helper.assertValueEqual(scene.countInStore(Items.BOW), 1, "the cancelled order never removes its warehouse weapon");
                helper.assertValueEqual(scene.countInStore(Items.ARROW), 16, "the cancelled order never removes ammunition");
                helper.assertTrue(scene.supplyState() == PatrolSupplies.State.CANCELLED, "supply cancellation remains observable");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 550)
    public static void aFullPackLeavesStorageUntouchedAndRecoversWhenRoomIsFreed(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        for (int slot = 0; slot < scene.body.pack().getContainerSize(); slot++) {
            scene.body.pack().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        scene.store.setItem(0, new ItemStack(Items.BOW)); scene.store.setItem(1, new ItemStack(Items.ARROW, 8));
        scene.supplies.loadout(scene.body, PatrolSupplies.Loadout.BOW); scene.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(scene.supplyState() == PatrolSupplies.State.NO_ROOM, "full-pack refusal is observable" + scene.debug()))
            .thenIdle(30)
            .thenExecute(() -> {
                helper.assertValueEqual(scene.countInStore(Items.BOW), 1, "no weapon is taken before capacity exists");
                helper.assertTrue(scene.level.getEntitiesOfClass(ItemEntity.class, scene.body.getBoundingBox().inflate(3)).isEmpty(),
                    "full-pack supply spills no actual equipment");
                scene.body.pack().setItem(0, ItemStack.EMPTY); scene.body.pack().setItem(1, ItemStack.EMPTY);
            })
            .thenWaitUntil(() -> helper.assertTrue(scene.body.pack().count(Items.BOW) == 1
                && scene.body.pack().count(Items.ARROW) == 8, "a finite retry uses newly available pack room" + scene.debug()))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.countInStore(Items.BOW), 0, "the actual bow moves once after capacity recovers");
                helper.assertValueEqual(scene.countInStore(Items.ARROW), 0, "the actual eight arrows move once");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 450)
    public static void deathBeforeCollectionDropsExistingEquipmentOnceAndLeavesStockAtSource(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ItemStack sword = new ItemStack(Items.IRON_SWORD); sword.setDamageValue(13);
        scene.body.pack().insert(sword);
        scene.store.setItem(0, new ItemStack(Items.BOW));
        scene.supplies.loadout(scene.body, PatrolSupplies.Loadout.BOW); scene.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(scene.supplyState() == PatrolSupplies.State.FETCHING, "the supply trip begins" + scene.debug()))
            .thenExecute(() -> scene.body.hurt(scene.level.damageSources().genericKill(), 1000))
            .thenIdle(30)
            .thenExecute(() -> {
                helper.assertValueEqual(scene.countInStore(Items.BOW), 1, "death before arrival never removes warehouse stock");
                var drops = scene.level.getEntitiesOfClass(ItemEntity.class, scene.body.getBoundingBox().inflate(4), item -> item.getItem().is(Items.IRON_SWORD));
                helper.assertValueEqual(drops.stream().mapToInt(item -> item.getItem().getCount()).sum(), 1, "already-owned equipment drops exactly once");
                helper.assertValueEqual(drops.getFirst().getItem().getDamageValue(), 13, "death preserves the pre-existing equipment's durability");
                scene.colony.holdingOf(scene.body.id()).ifPresent(held -> scene.colony.release(held.id(), Release.DIED));
                scene.supplies.tick(scene.level.getServer());
                helper.assertTrue(scene.supplyState() == PatrolSupplies.State.INACTIVE,
                    "after actual colony release the final cancellation status is no longer retained");
                helper.assertTrue(scene.supplies.preference(scene.body.id()).isEmpty(),
                    "a departed resident's saved loadout is removed with its per-resident state");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 450)
    public static void losingTheLastArrowDuringTheWindupDoesNotDuplicateOrSpendCarriedAmmo(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.body.pack().insert(new ItemStack(Items.BOW)); scene.body.pack().insert(new ItemStack(Items.ARROW, 7));
        scene.store.setItem(0, new ItemStack(Items.ARROW)); scene.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(scene.supplyState() == PatrolSupplies.State.FETCHING && scene.nearStore(),
                "the resident reaches the real last-arrow source before committing its collection" + scene.debug()))
            .thenExecute(() -> scene.store.removeItem(0, 1))
            .thenWaitUntil(() -> helper.assertTrue(scene.supplyState() == PatrolSupplies.State.NO_STOCK,
                "an execution-time competitor taking the last arrow creates a bounded stockout" + scene.debug()))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.body.pack().count(Items.ARROW), 7, "failed collection does not consume or refund copies of carried ammunition");
                helper.assertValueEqual(scene.countInStore(Items.ARROW), 0, "the competing withdrawal remains the only source mutation");
                helper.assertValueEqual(scene.weaponCount(), 1, "failed collection leaves the actual weapon intact");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 450)
    public static void storageWithdrawalAtArrivalCannotReadAnAdjacentUnregisteredContainer(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.body.pack().insert(new ItemStack(Items.BOW));
        scene.store.setItem(0, new ItemStack(Items.ARROW, 8));
        helper.setBlock(new BlockPos(19, 1, 3), Blocks.BARREL);
        Container unregistered = Containers.at(scene.level, scene.at(19, 1, 3).block(scene.level)).orElseThrow();
        unregistered.setItem(0, new ItemStack(Items.ARROW, 8));
        scene.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(scene.supplyState() == PatrolSupplies.State.FETCHING && scene.nearStore(),
                "the resident reaches its registered source" + scene.debug()))
            .thenExecute(() -> scene.colony.stores(Scene.STORE_OWNER, scene.level.dimension(), List.of()))
            .thenWaitUntil(() -> helper.assertTrue(scene.supplyState() == PatrolSupplies.State.NO_STOCK,
                "source deregistration is checked again at execution" + scene.debug()))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.body.pack().count(Items.ARROW), 0, "deregistered source supplies nothing");
                helper.assertValueEqual(scene.countInStore(Items.ARROW), 8, "the original source keeps all actual arrows");
                helper.assertValueEqual(unregistered.getItem(0).getCount(), 8, "nearby unregistered inventory is never a fallback source");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void aLaterExtractionAndRestoreAdapterFailurePreserveEarlierComponentStacks(GameTestHelper helper) {
        extractionFailure(helper, false);
        extractionFailure(helper, true);
        helper.succeed();
    }

    private static void extractionFailure(GameTestHelper helper, boolean afterMutation) {
        Scene scene = new Scene(helper);
        scene.body.pack().insert(new ItemStack(Items.BOW)); scene.guard();
        SimpleContainer failing = new SimpleContainer(3) {
            @Override public ItemStack removeItem(int slot, int count) {
                if (slot == 2) {
                    if (afterMutation) { super.removeItem(slot, count); }
                    throw new IllegalStateException("third component stack failed extraction");
                }
                return super.removeItem(slot, count);
            }
            @Override public boolean canPlaceItem(int slot, ItemStack stack) {
                throw new IllegalStateException("adapter refuses restoration");
            }
        };
        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = new ItemStack(Items.ARROW, slot == 2 ? 4 : 2);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("arrow component " + slot));
            failing.setItem(slot, stack);
        }
        Colony bridged = (Colony) java.lang.reflect.Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[] {Colony.class},
            (proxy, method, arguments) -> method.getName().equals("store") ? java.util.Optional.of(failing) : method.invoke(scene.colony, arguments));
        PatrolSupplies supplies = new PatrolSupplies(bridged);
        Worker worker = worker(scene.body);
        Node request = supplies.urges(worker, scene.commands.current(scene.body.id()), Long.MIN_VALUE, Long.MIN_VALUE)
            .getFirst().take().get();
        BlockPos at = scene.source.block(scene.level);
        scene.body.moveTo(at.getX() - 2.5, at.getY(), at.getZ() + .5);
        try {
            try {
                Commit.run(scene.level, request, worker);
                helper.fail("the failing third extraction must propagate its adapter error");
            } catch (IllegalStateException expected) {
                helper.assertTrue(expected.getMessage().contains("third component"), "the original draw failure remains visible");
            }
            request.released(scene.level, worker, new Ending.Failed(request.id(), LaborRefusal.WORK_BROKE));
            request.released(scene.level, worker, Ending.DROPPED);
            helper.assertValueEqual(scene.body.pack().count(Items.ARROW), afterMutation ? 8 : 4, "all physically drawn arrows fall back into the real pack");
            helper.assertValueEqual(failing.getItem(2).getCount(), afterMutation ? 0 : 4, "the final source stack agrees with whether extraction physically occurred");
            helper.assertTrue(failing.getItem(0).isEmpty() && failing.getItem(1).isEmpty(), "restoration failure does not invent source copies");
            for (int component = 0; component < 2; component++) {
                Component name = Component.literal("arrow component " + component);
                helper.assertValueEqual(scene.body.pack().contents().stream().filter(stack -> name.equals(stack.get(DataComponents.CUSTOM_NAME)))
                    .mapToInt(ItemStack::getCount).sum(), 2, "every successfully drawn component survives exactly once");
            }
            if (afterMutation) {
                helper.assertValueEqual(scene.body.pack().contents().stream().filter(stack ->
                    Component.literal("arrow component 2").equals(stack.get(DataComponents.CUSTOM_NAME)))
                    .mapToInt(ItemStack::getCount).sum(), 4, "an adapter exception after mutation preserves the unreturned current stack too");
            }
        } finally { scene.close(); }
    }

    private static Worker worker(ResidentEntity body) {
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body; }
            public Container pack() { return body.pack(); }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { body.spawnAtLocation(stack); }
            public Effects effects() { return body.effects(); }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }

    private static final class Scene implements GameTestListener {
        static final ResourceLocation STORE_OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "test_patrol_supply_store");
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ResidentEntity body;
        final ResidentCommands commands;
        final PatrolSupplies supplies;
        final WorldPos anchor;
        final WorldPos source;
        final Container store;
        final LaborFixture simulation;
        final List<Entity> others = new ArrayList<>();
        boolean leftAnchor;
        boolean closed;
        int previousStock;

        Scene(GameTestHelper helper) {
            this.helper = helper; level = helper.getLevel();
            for (int x = 0; x < 23; x++) {
                for (int z = 0; z < 9; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
            }
            helper.setBlock(new BlockPos(18, 1, 3), Blocks.BARREL);
            colony = Colonies.mint(level.getServer());
            commands = ResidentCommands.of(colony); supplies = PatrolSupplies.of(colony);
            anchor = at(2, 1, 3); source = at(18, 1, 3);
            store = Containers.at(level, source.block(level)).orElseThrow();
            colony.stores(STORE_OWNER, level.dimension(), List.of(source));
            body = PersonBody.RESIDENT.get().create(level);
            BlockPos start = anchor.block(level); body.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5);
            colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id()); level.addFreshEntity(body);
            simulation = new LaborFixture(colony, level);
            helper.onEachTick(() -> {
                previousStock = countInStore(Items.BOW) + countInStore(Items.ARROW);
                simulation.tick();
                int after = countInStore(Items.BOW) + countInStore(Items.ARROW);
                if (after < previousStock) { helper.assertTrue(nearStore(), "stock only moves after this resident physically reaches the container"); }
                leftAnchor |= !Reach.standingAt(body.position(), anchor.block(level));
            });
            helper.testInfo.addListener(this);
        }

        WorldPos at(int x, int y, int z) { return WorldPos.of(level, helper.absolutePos(new BlockPos(x, y, z))); }
        void guard() { commands.guard(body, anchor, new Vec3(1, 0, 0)); }
        PatrolSupplies.State supplyState() { return supplies.status(body.id()).state(); }
        String debug() {
            return " [status=" + supplies.status(body.id()) + ", preference=" + supplies.preference(body.id())
                + ", order=" + commands.current(body.id()).map(order -> order.mode() + "/" + order.status())
                + ", body=" + body.blockPosition() + ", source=" + source
                + ", registered=" + colony.storesIn(level.dimension()).contains(source) + "/" + colony.storesIn(level.dimension()).size() + ", rules=" + colony.storeRules(source).size()
                + ", resolved=" + colony.store(level, source).map(container -> container.getClass().getName()
                    + "/bow=" + io.github.izakyl.folkways.core.api.terms.Goods.countIn(container,
                        io.github.izakyl.folkways.core.api.terms.ItemSpec.of(ResourceLocation.withDefaultNamespace("bow")))
                    + "/arrow=" + io.github.izakyl.folkways.core.api.terms.Goods.countIn(container,
                        io.github.izakyl.folkways.core.api.terms.ItemSpec.of(net.minecraft.tags.ItemTags.ARROWS)))
                + ", fixtureStock=" + countInStore(Items.BOW) + "/" + countInStore(Items.ARROW)
                + ", pack=bow:" + body.pack().count(Items.BOW) + "/arrows:" + body.pack().count(Items.ARROW) + "/used:" + body.pack().contents().size() + ", hand=" + body.getMainHandItem()
                + ", footings=" + Reach.workableCells(level, source.block(level)).size()
                + ", claims=" + colony.reservedInputs(level).size() + "]";
        }
        boolean nearStore() { return Reach.withinReach(body.blockPosition(), source.block(level)); }
        int countInStore(net.minecraft.world.item.Item item) {
            int count = 0;
            for (int slot = 0; slot < store.getContainerSize(); slot++) {
                if (store.getItem(slot).is(item)) { count += store.getItem(slot).getCount(); }
            }
            return count;
        }
        ItemStack actualWeapon() {
            return body.hasWeaponSession() ? body.getMainHandItem() : body.pack().contents().stream().filter(stack -> stack.is(Items.BOW)).findFirst().orElse(ItemStack.EMPTY);
        }
        int weaponCount() { return body.pack().count(Items.BOW) + (body.hasWeaponSession() && body.getMainHandItem().is(Items.BOW) ? body.getMainHandItem().getCount() : 0); }
        Zombie enemy(int x) {
            Zombie enemy = EntityType.ZOMBIE.create(level); enemy.setNoAi(true);
            enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            BlockPos feet = at(x, 1, 3).block(level); enemy.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
            level.addFreshEntity(enemy); others.add(enemy); return enemy;
        }
        void close() {
            if (closed) { return; }
            closed = true;
            simulation.close(); body.discard(); others.forEach(Entity::discard); colony.raze();
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
