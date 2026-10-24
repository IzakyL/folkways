package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WeaponSessionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WeaponSessionGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void bowUsesActualArrowsAndNativeDurabilityAndHurtsItsTarget(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 7);
        ResidentEntity body = fixture.body();
        ItemStack ammo = new ItemStack(Items.ARROW, 3);
        ammo.set(DataComponents.CUSTOM_NAME, Component.literal("actual bow ammunition"));
        body.pack().insert(ammo);
        WeaponSession session = Weapons.open(worker(body)).orElseThrow();
        helper.assertValueEqual(body.pack().count(Items.BOW), 0, "the pack does not retain a duplicate bow");
        helper.assertTrue(body.getMainHandItem().is(Items.BOW), "the actual bow is equipped");
        helper.assertFalse(Weapons.open(worker(body)).isPresent(), "a second session cannot claim the hand");
        helper.assertTrue(session.fire(fixture.target()), "a real arrow permits drawing");
        AtomicInteger seen = ticking(helper, fixture, session);
        helper.runAfterDelay(45, () -> {
            try {
                helper.assertTrue(seen.get() > 0, "native shooting creates an arrow owned by the resident");
                helper.assertTrue(fixture.target().getHealth() < fixture.target().getMaxHealth(), "the native arrow hits its target");
                helper.assertValueEqual(body.pack().count(Items.ARROW), 2, "one actual arrow is consumed");
                session.close(); session.close();
                ItemStack returned = find(body, Items.BOW);
                helper.assertValueEqual(returned.getDamageValue(), 8, "native shooting wears the actual bow once");
                helper.assertValueEqual(returned.get(DataComponents.CUSTOM_NAME), Component.literal("actual weapon"),
                    "the actual weapon's components survive the loan");
                helper.assertValueEqual(body.pack().count(Items.BOW), 1, "closing twice returns only one bow");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void crossbowNativeReloadPersistsARealChargedProjectileAcrossSave(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.CROSSBOW, 4);
        ResidentEntity body = fixture.body();
        ItemStack arrow = new ItemStack(Items.TIPPED_ARROW, 3);
        arrow.set(DataComponents.CUSTOM_NAME, Component.literal("loaded arrow component"));
        body.pack().insert(arrow);
        WeaponSession session = Weapons.open(worker(body)).orElseThrow();
        helper.assertTrue(session.reload(), "an actual crossbow begins native reload");
        AtomicInteger seen = ticking(helper, fixture, session);
        helper.runAfterDelay(30, () -> {
            helper.assertValueEqual(session.state(), WeaponSession.State.READY, "reload finishes without firing");
            helper.assertValueEqual(body.pack().count(Items.TIPPED_ARROW), 2, "native reload consumes one real arrow");
            helper.assertTrue(CrossbowItem.isCharged(body.getMainHandItem()), "the native charged component is present");
            helper.assertValueEqual(seen.get(), 0, "reloading does not shoot");
            CompoundTag saved = body.saveWithoutId(new CompoundTag());
            helper.assertValueEqual(session.state(), WeaponSession.State.CLOSED, "saving cancels the live session before attachment serialization");
            body.load(saved);
            ItemStack actual = find(body, Items.CROSSBOW);
            helper.assertTrue(CrossbowItem.isCharged(actual), "the saved pack retains the native loaded projectile");
            helper.assertValueEqual(actual.get(DataComponents.CHARGED_PROJECTILES).getItems().getFirst()
                .get(DataComponents.CUSTOM_NAME), Component.literal("loaded arrow component"),
                "ammunition components survive native reload and saving");
            WeaponSession loaded = Weapons.open(worker(body)).orElseThrow();
            helper.assertTrue(loaded.fire(fixture.target()), "the saved charged crossbow fires without fresh ammo consumption");
            helper.onEachTick(loaded::tick);
            helper.runAfterDelay(12, () -> {
                try {
                    helper.assertTrue(fixture.target().getHealth() < fixture.target().getMaxHealth(), "the native crossbow projectile hits");
                    loaded.close();
                    ItemStack returned = find(body, Items.CROSSBOW);
                    helper.assertFalse(CrossbowItem.isCharged(returned), "native shooting clears the charged component");
                    helper.assertValueEqual(returned.getDamageValue(), 5, "native firing wears the same actual crossbow");
                    helper.assertValueEqual(body.pack().count(Items.TIPPED_ARROW), 2, "firing consumes the loaded arrow only");
                    helper.assertValueEqual(body.pack().count(Items.CROSSBOW), 1, "save and shooting retain one crossbow");
                } finally { fixture.close(); }
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void stoppingABowRejectsFurtherFireAndCreatesNoProjectile(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 11);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 2));
        WeaponSession session = Weapons.open(worker(fixture.body())).orElseThrow();
        helper.assertTrue(session.fire(fixture.target()), "draw starts with actual ammunition");
        AtomicInteger seen = ticking(helper, fixture, session);
        helper.runAfterDelay(10, () -> {
            session.stop();
            helper.assertFalse(session.fire(fixture.target()), "stopped sessions reject new fire intents");
        });
        helper.runAfterDelay(40, () -> {
            try {
                helper.assertValueEqual(seen.get(), 0, "stopping never invokes bow release");
                helper.assertValueEqual(fixture.body().pack().count(Items.ARROW), 2, "cancelled draw spends no arrow");
                session.close(); session.tick(); session.close();
                helper.assertValueEqual(find(fixture.body(), Items.BOW).getDamageValue(), 11, "cancelled draw wears nothing");
                helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 1, "stopped bow returns once");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void savingDuringBowDrawKeepsOneActualWeaponAndCancelsFire(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 9);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 2));
        WeaponSession session = Weapons.open(worker(fixture.body())).orElseThrow();
        session.fire(fixture.target());
        AtomicInteger seen = ticking(helper, fixture, session);
        helper.runAfterDelay(10, () -> {
            CompoundTag saved = fixture.body().saveWithoutId(new CompoundTag());
            helper.assertValueEqual(session.state(), WeaponSession.State.CLOSED, "saving closes before pack serialization");
            fixture.body().load(saved);
            helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 1, "the snapshot retains one bow");
            helper.assertTrue(fixture.body().getMainHandItem().isEmpty(), "there is no saved hand duplicate");
            helper.assertValueEqual(find(fixture.body(), Items.BOW).getDamageValue(), 9, "saved durability is exact");
        });
        helper.runAfterDelay(40, () -> {
            try {
                helper.assertValueEqual(seen.get(), 0, "the pre-save callback cannot fire afterwards");
                helper.assertValueEqual(fixture.body().pack().count(Items.ARROW), 2, "saving during draw spends no arrow");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void targetBecomingAlliedDuringDrawPreventsDischarge(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 0);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 1));
        WeaponSession session = Weapons.open(worker(fixture.body())).orElseThrow();
        session.fire(fixture.target());
        AtomicInteger seen = ticking(helper, fixture, session);
        helper.runAfterDelay(10, () -> {
            var board = helper.getLevel().getScoreboard();
            var team = board.addPlayerTeam("weapon_" + fixture.body().getUUID().toString().substring(0, 8));
            board.addPlayerToTeam(fixture.body().getScoreboardName(), team);
            board.addPlayerToTeam(fixture.target().getScoreboardName(), team);
        });
        helper.runAfterDelay(35, () -> {
            try {
                helper.assertValueEqual(seen.get(), 0, "hostility is checked during charging and at discharge");
                helper.assertValueEqual(fixture.body().pack().count(Items.ARROW), 1, "revoked permission consumes no ammunition");
                session.close();
            } finally {
                var team = fixture.body().getTeam();
                if (team != null) { helper.getLevel().getScoreboard().removePlayerTeam((net.minecraft.world.scores.PlayerTeam) team); }
                fixture.close();
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large")
    public static void aFullPackSpillsTheActualWeaponOnlyOnceOnClose(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 17);
        WeaponSession session = Weapons.open(worker(fixture.body())).orElseThrow();
        for (int slot = 0; slot < fixture.body().pack().getContainerSize(); slot++) {
            fixture.body().pack().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        try {
            session.close(); session.close();
            List<ItemEntity> swords = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                fixture.body().getBoundingBox().inflate(2), item -> item.getItem().is(Items.IRON_SWORD));
            helper.assertValueEqual(swords.stream().mapToInt(item -> item.getItem().getCount()).sum(), 1,
                "overflow returns exactly one real sword as a drop");
            helper.assertValueEqual(swords.getFirst().getItem().getDamageValue(), 17, "drop keeps durability");
            helper.assertValueEqual(swords.getFirst().getItem().get(DataComponents.CUSTOM_NAME),
                Component.literal("actual weapon"), "drop keeps components");
            swords.forEach(Entity::discard);
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void deathDuringCrossbowReloadDropsInventoryAndWeaponOnce(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.CROSSBOW, 6);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 3));
        WeaponSession session = Weapons.open(worker(fixture.body())).orElseThrow();
        session.fire(fixture.target());
        AtomicInteger seen = ticking(helper, fixture, session);
        helper.runAfterDelay(10, () -> fixture.body().hurt(helper.getLevel().damageSources().genericKill(), 1000));
        helper.runAfterDelay(40, () -> {
            try {
                session.close(); session.tick();
                helper.assertValueEqual(session.state(), WeaponSession.State.CLOSED, "death terminates reload");
                helper.assertValueEqual(seen.get(), 0, "a dead resident cannot discharge a pending crossbow");
                List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    fixture.target().getBoundingBox().inflate(12));
                helper.assertValueEqual(drops.stream().filter(item -> item.getItem().is(Items.CROSSBOW))
                    .mapToInt(item -> item.getItem().getCount()).sum(), 1, "death drops one actual crossbow");
                helper.assertValueEqual(drops.stream().filter(item -> item.getItem().is(Items.ARROW))
                    .mapToInt(item -> item.getItem().getCount()).sum(), 3, "cancelled reload drops all unspent ammunition");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large")
    public static void unloadAndDismissalCloseTheLoanAndNoAmmoCannotFire(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 0);
        try {
            helper.assertFalse(Weapons.armed(worker(fixture.body())), "an empty bow is not a usable combat weapon");
            helper.assertTrue(Weapons.open(worker(fixture.body())).isEmpty(), "automatic selection skips an empty bow");
            fixture.body().pack().insert(new ItemStack(Items.IRON_SWORD));
            helper.assertFalse(Weapons.ranged(worker(fixture.body())), "an empty bow falls back to the real carried sword");
            WeaponSession sword = Weapons.open(worker(fixture.body())).orElseThrow();
            helper.assertTrue(fixture.body().getMainHandItem().is(Items.IRON_SWORD), "the selected fallback is the actual sword");
            sword.close();
            WeaponSession session = Weapons.BOW.open(worker(fixture.body())).orElseThrow();
            helper.assertFalse(session.fire(fixture.target()), "no arrow means no bow shot");
            fixture.body().leaveColony();
            helper.assertValueEqual(session.state(), WeaponSession.State.CLOSED, "dismissal closes the loan immediately");
            helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 1, "dismissal returns one bow");
            WeaponSession next = Weapons.BOW.open(worker(fixture.body())).orElseThrow();
            fixture.body().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            next.tick(); next.close();
            helper.assertValueEqual(next.state(), WeaponSession.State.CLOSED, "the unload lifecycle closes the next loan");
            helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 1, "unload returns one bow");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aRazedColonyRevokesPendingFireBeforeTheResidentsPeriodicCleanup(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, 0);
        var colony = Colonies.mint(helper.getLevel().getServer());
        fixture.body().joinColony(colony.id());
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 2));
        WeaponSession session = Weapons.open(worker(fixture.body())).orElseThrow();
        helper.assertTrue(session.fire(fixture.target()), "the colony resident can begin an attack");
        try {
            colony.raze();
            session.tick();
            helper.assertValueEqual(session.state(), WeaponSession.State.CLOSED, "raze invalidates pending fire immediately");
            helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 1, "raze closes the actual loan once");
            helper.assertValueEqual(fixture.body().pack().count(Items.ARROW), 2, "raze spends no ammunition");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void replacingADrawnBowWithAnotherBowClosesTheOldLoan(GameTestHelper helper) {
        replacingDuringDraw(helper, Items.BOW);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void replacingADrawnBowWithANonWeaponKeepsBothActualItemsOnce(GameTestHelper helper) {
        replacingDuringDraw(helper, Items.STICK);
    }

    private static void replacingDuringDraw(GameTestHelper helper, Item replacementItem) {
        Fixture fixture = fixture(helper, Items.BOW, 9);
        ResidentEntity body = fixture.body();
        body.pack().insert(new ItemStack(Items.ARROW, 3));
        WeaponSession session = Weapons.open(worker(body)).orElseThrow();
        helper.assertTrue(session.fire(fixture.target()), "the original real bow begins drawing");
        ItemStack replacement = new ItemStack(replacementItem);
        int replacementDamage = replacement.isDamageableItem() ? 23 : 0;
        replacement.setDamageValue(replacementDamage);
        replacement.set(DataComponents.CUSTOM_NAME, Component.literal("external replacement"));
        AtomicInteger seen = ticking(helper, fixture, session);
        helper.runAfterDelay(10, () -> {
            body.setItemSlot(EquipmentSlot.MAINHAND, replacement);
            helper.assertValueEqual(session.state(), WeaponSession.State.CLOSED, "external replacement closes the old pending session immediately");
            helper.assertTrue(body.getMainHandItem() == replacement && !body.hasWeaponSession(),
                "the replacement is installed without becoming owned by the previous session");
            helper.assertFalse(session.fire(fixture.target()), "the old session cannot adopt the replacement for another shot");
            helper.assertValueEqual(find(body, Items.BOW).getDamageValue(), 9, "the original loan returns with its exact durability");
        });
        helper.runAfterDelay(40, () -> {
            try {
                session.close(); session.tick();
                helper.assertValueEqual(seen.get(), 0, "replacing the hand cancels the original pending projectile");
                helper.assertValueEqual(body.pack().count(Items.ARROW), 3, "replacement spends no ammunition");
                var original = body.pack().contents().stream().filter(stack ->
                    Component.literal("actual weapon").equals(stack.get(DataComponents.CUSTOM_NAME))).toList();
                var external = body.pack().contents().stream().filter(stack ->
                    Component.literal("external replacement").equals(stack.get(DataComponents.CUSTOM_NAME))).toList();
                helper.assertValueEqual(original.size(), 1, "the original components identify exactly one returned weapon");
                helper.assertValueEqual(original.getFirst().getDamageValue(), 9, "the original durability remains unchanged");
                helper.assertValueEqual(external.size(), 1, "normal stray-hand recovery retains the replacement exactly once");
                helper.assertTrue(external.getFirst().is(replacementItem), "the replacement keeps its own item identity");
                helper.assertValueEqual(external.getFirst().getDamageValue(), replacementDamage, "the replacement keeps its own durability");
                helper.assertTrue(body.getMainHandItem().isEmpty(), "neither actual item leaves a duplicate in the hand");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void nativeBowBreakageDoesNotReturnAnExhaustedWeapon(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.BOW, new ItemStack(Items.BOW).getMaxDamage() - 1);
        fixture.body().pack().insert(new ItemStack(Items.ARROW, 1));
        WeaponSession session = Weapons.open(worker(fixture.body())).orElseThrow();
        helper.assertTrue(session.fire(fixture.target()), "the last real arrow can discharge the nearly broken bow");
        AtomicInteger seen = ticking(helper, fixture, session);
        helper.onEachTick(() -> {
            if (!fixture.body().hasWeaponSession()) {
                helper.assertValueEqual(session.state(), WeaponSession.State.CLOSED,
                    "native breakage leaves the old session terminal in the same tick");
            }
        });
        helper.runAfterDelay(40, () -> {
            try {
                session.close(); session.close();
                helper.assertTrue(seen.get() > 0, "native shooting occurs before native breakage");
                helper.assertValueEqual(fixture.body().pack().count(Items.BOW), 0, "closing never resurrects the exhausted bow");
                helper.assertValueEqual(fixture.body().pack().count(Items.ARROW), 0, "native shooting spends the last actual arrow");
                helper.assertTrue(fixture.body().getMainHandItem().isEmpty(), "no exhausted weapon remains in the hand");
                helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    fixture.body().getBoundingBox().inflate(3), item -> item.getItem().is(Items.BOW)).isEmpty(),
                    "native breakage cannot spill a replacement copy");
            } finally { fixture.close(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anExplicitBowPreferenceFiresTheUsableBowAfterAnEarlierSword(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 3);
        Colony colony = enroll(helper, fixture.body());
        ResidentEntity body = fixture.body();
        ItemStack bow = new ItemStack(Items.BOW);
        bow.setDamageValue(7); bow.set(DataComponents.CUSTOM_NAME, Component.literal("preferred real bow"));
        body.pack().insert(bow); body.pack().insert(new ItemStack(Items.ARROW, 3));
        PatrolSupplies supplies = PatrolSupplies.of(colony);
        supplies.loadout(body, LoadoutRegistry.BOW);
        WeaponSession session = Weapons.open(worker(body)).orElseThrow();
        helper.assertTrue(body.getMainHandItem().is(Items.BOW), "the usable explicitly preferred bow takes precedence over a sword earlier in the pack");
        helper.assertTrue(supplies.preference(body.id()).orElseThrow() == LoadoutRegistry.BOW, "the explicit preference is observable without mutating it");
        helper.assertTrue(session.fire(fixture.target()), "the selected actual bow starts a native shot");
        AtomicInteger seen = ticking(helper, fixture, session);
        helper.runAfterDelay(40, () -> {
            try {
                helper.assertTrue(seen.get() > 0 && fixture.target().getHealth() < fixture.target().getMaxHealth(),
                    "preference selection produces a real arrow and real target damage");
                helper.assertValueEqual(body.pack().count(Items.ARROW), 2, "the preferred shot consumes one actual arrow");
                session.close();
                helper.assertValueEqual(find(body, Items.BOW).getDamageValue(), 8, "the preferred real bow receives native wear");
                helper.assertValueEqual(find(body, Items.IRON_SWORD).getDamageValue(), 3, "the preceding actual sword remains untouched");
                helper.assertValueEqual(body.pack().count(Items.BOW), 1, "the preferred weapon returns once");
                helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 1, "selection does not remove the player's other weapon");
            } finally { fixture.close(); colony.raze(); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void changingPreferenceStopsTheOldDrawAndAnEmptyPreferredBowFallsBackToASword(GameTestHelper helper) {
        Fixture fixture = fixture(helper, Items.IRON_SWORD, 3);
        Colony colony = enroll(helper, fixture.body());
        ResidentEntity body = fixture.body();
        ItemStack bow = new ItemStack(Items.BOW);
        bow.setDamageValue(7); bow.set(DataComponents.CUSTOM_NAME, Component.literal("cancelled preferred bow"));
        body.pack().insert(bow); body.pack().insert(new ItemStack(Items.ARROW, 2));
        PatrolSupplies supplies = PatrolSupplies.of(colony);
        supplies.loadout(body, LoadoutRegistry.BOW);
        WeaponSession session = Weapons.open(worker(body)).orElseThrow();
        helper.assertTrue(session.fire(fixture.target()), "the preferred bow begins drawing");
        AtomicInteger seen = ticking(helper, fixture, session);
        helper.runAfterDelay(10, () -> {
            supplies.loadout(body, LoadoutRegistry.BOW);
            helper.assertValueEqual(session.state(), WeaponSession.State.FIRING, "reapplying the same preference does not cancel an active draw");
            supplies.loadout(body, LoadoutRegistry.MELEE);
            helper.assertValueEqual(session.state(), WeaponSession.State.CLOSED, "an actual preference change closes the old local session immediately");
            helper.assertTrue(!body.hasWeaponSession() && body.getMainHandItem().isEmpty(), "normal closure returns the old actual weapon before reselection");
            helper.assertValueEqual(body.pack().count(Items.BOW), 1, "the cancelled bow returns once");
            WeaponSession melee = Weapons.open(worker(body)).orElseThrow();
            helper.assertTrue(body.getMainHandItem().is(Items.IRON_SWORD), "the changed preference selects the actual melee weapon");
            melee.close();
            for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
                if (body.pack().getItem(slot).is(Items.ARROW)) { body.pack().removeItemNoUpdate(slot); }
            }
            supplies.loadout(body, LoadoutRegistry.BOW);
            WeaponSession fallback = Weapons.open(worker(body)).orElseThrow();
            helper.assertTrue(body.getMainHandItem().is(Items.IRON_SWORD), "an empty preferred bow still falls back to a usable carried sword");
            fallback.close();
        });
        helper.runAfterDelay(40, () -> {
            try {
                helper.assertValueEqual(seen.get(), 0, "the old cancelled draw creates no projectile after the preference changed");
                helper.assertValueEqual(fixture.target().getHealth(), fixture.target().getMaxHealth(), "preference switching never finishes the pending shot");
                helper.assertValueEqual(find(body, Items.BOW).getDamageValue(), 7, "the cancelled original keeps its durability");
                helper.assertValueEqual(find(body, Items.BOW).get(DataComponents.CUSTOM_NAME), Component.literal("cancelled preferred bow"),
                    "preference switching preserves the original components");
                helper.assertValueEqual(body.pack().count(Items.BOW), 1, "multiple preference changes retain one actual bow");
                helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 1, "multiple selections retain one actual sword");
            } finally { fixture.close(); colony.raze(); }
            helper.succeed();
        });
    }

    private static Colony enroll(GameTestHelper helper, ResidentEntity body) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        return colony;
    }

    private static AtomicInteger ticking(GameTestHelper helper, Fixture fixture, WeaponSession session) {
        AtomicInteger seen = new AtomicInteger();
        helper.onEachTick(() -> {
            session.tick();
            int arrows = helper.getLevel().getEntitiesOfClass(AbstractArrow.class,
                fixture.body().getBoundingBox().inflate(20), arrow -> arrow.getOwner() == fixture.body()).size();
            seen.accumulateAndGet(arrows, Math::max);
        });
        return seen;
    }

    private static Fixture fixture(GameTestHelper helper, Item item, int damage) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 4; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
        }
        ResidentEntity body = PersonBody.RESIDENT.get().create(helper.getLevel());
        Zombie target = EntityType.ZOMBIE.create(helper.getLevel());
        body.setNoAi(true); target.setNoAi(true);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR).setBaseValue(0);
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        body.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        target.moveTo(at.getX() + 6.5, at.getY(), at.getZ() + .5);
        helper.getLevel().addFreshEntity(body);
        helper.getLevel().addFreshEntity(target);
        ItemStack actual = new ItemStack(item);
        actual.setDamageValue(damage);
        actual.set(DataComponents.CUSTOM_NAME, Component.literal("actual weapon"));
        body.pack().insert(actual);
        return new Fixture(body, target);
    }

    private record Fixture(ResidentEntity body, Zombie target) {
        void close() { body.discard(); target.discard(); }
    }

    private static ItemStack find(ResidentEntity body, Item item) {
        return body.pack().contents().stream().filter(stack -> stack.is(item)).findFirst().orElse(ItemStack.EMPTY);
    }

    private static Worker worker(ResidentEntity body) {
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body; }
            public Container pack() { return body.pack(); }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) {
                return body.pack().contents().stream().filter(stack -> Tools.answers(stack, need)).findFirst().orElse(ItemStack.EMPTY);
            }
            public void spill(ItemStack stack) { body.spawnAtLocation(stack); }
            public Effects effects() { return body.effects(); }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }
}
