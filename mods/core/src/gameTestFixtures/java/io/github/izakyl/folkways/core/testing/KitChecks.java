package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.resident.api.KitCapabilities;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;

public final class KitChecks {

    private KitChecks() {
    }

    public static void residentFetchesAndReturns(GameTestHelper helper, ItemStack backpack, ItemStack upgrade,
                                               boolean crafts) {
        residentFetchesAndReturns(helper, backpack, upgrade, crafts, ItemStack.EMPTY, body -> { });
    }

    public static void residentFetchesAndReturns(GameTestHelper helper, ItemStack backpack, ItemStack upgrade,
                                               boolean crafts, ItemStack disc,
                                               Consumer<Body> ready) {
        residentFetchesAndReturns(helper, backpack, upgrade, crafts, disc, ready, GearSlots.CHEST);
    }

    public static void residentFetchesAndReturns(GameTestHelper helper, ItemStack backpack, ItemStack upgrade,
                                               boolean crafts, ItemStack disc, Consumer<Body> ready,
                                               ResourceLocation rootSlot) {
        var level = helper.getLevel();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos source = helper.absolutePos(new BlockPos(1, 1, 2));
        level.setBlockAndUpdate(source, Blocks.BARREL.defaultBlockState());
        var store = Containers.at(level, source).orElseThrow();
        CompoundTag opaque = new CompoundTag();
        opaque.putString("kit_test", "unchanged opaque backpack payload");
        backpack.set(DataComponents.CUSTOM_DATA, CustomData.of(opaque));
        backpack.set(DataComponents.CUSTOM_NAME, Component.literal("opaque kit"));
        ItemStack expected = backpack.copy();
        store.setItem(0, backpack.copy());
        store.setItem(1, backpack.copy());
        if (!upgrade.isEmpty()) {
            store.setItem(2, upgrade.copy());
        }
        if (!disc.isEmpty()) {
            store.setItem(3, disc.copy());
        }
        var colony = Colonies.mint(level.getServer());
        var owner = ResourceLocation.fromNamespaceAndPath("folkways_core", "kit_test");
        WorkDefaults.set(colony, PersonBody.ID, Vocations.HAULING, true, 1);
        WorkDefaults.set(colony, PersonBody.ID, WaresContent.CRAFTING, true, 1);
        var body = PersonBody.RESIDENT.get().create(level);
        BlockPos start = helper.absolutePos(new BlockPos(2, 1, 2));
        body.moveTo(start.getBottomCenter());
        body.setNoAi(true);
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        colony.stores(owner, level.dimension(), List.of(WorldPos.of(level, source)));
        var profiles = EquipmentProfiles.of(colony);
        var profile = profiles.create("Carried tools");
        var parent = EquipmentProfile.Entry.slotted(rootSlot, ItemSpec.of(backpack.getItem()));
        profiles.add(profile.id(), parent);
        if (!upgrade.isEmpty()) {
            var child = EquipmentProfile.Entry.carried(ItemSpec.of(upgrade.getItem()), 1, 1).under(parent.id());
            profiles.add(profile.id(), child);
            if (!disc.isEmpty()) {
                profiles.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(disc.getItem()), 1, 1)
                    .under(child.id()));
            }
        }
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
        var labor = new LaborFixture(colony, level);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger made = new AtomicInteger();
        AtomicBoolean offered = new AtomicBoolean();
        Cleanup.on(helper, () -> {
            running.set(false);
            labor.close();
            body.discard();
            colony.raze();
        });
        helper.onEachTick(() -> {
            if (running.get()) {
                labor.tick();
                for (var node : labor.route(body)) {
                    if (node.spec().owner().equals(WaresContent.ID)) {
                        helper.assertTrue(node.worker().filter(body.id()::equals).isPresent()
                            && node.spec().site() instanceof WorkSite.AtEntity
                            && node.spec().stances() == Stances.WHEREVER, "carried crafting belongs to its bearer");
                        offered.set(true);
                    }
                }
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            EquipmentKits.parts(body, profiles.assigned(body.id()).orElseThrow()).values().stream()
                .flatMap(List::stream).mapToInt(part -> part.stack().getCount()).sum()
                == (upgrade.isEmpty() ? 1 : disc.isEmpty() ? 2 : 3),
            "kit fetched: " + labor.diagnosis() + " route=" + labor.route(body)))
            .thenExecute(() -> {
                helper.assertTrue(body.pack().count(backpack.getItem()) == 0,
                    "held kit does not occupy the ordinary pack");
                helper.assertValueEqual(GearSlots.slot(rootSlot).orElseThrow().worn(body)
                    .get(DataComponents.CUSTOM_DATA),
                    expected.get(DataComponents.CUSTOM_DATA), "opaque backpack data survives native initialization");
                helper.assertTrue(body.pack().plannableCells(item -> false) > 18,
                    "held provider gear contributes plannable capacity");
                var observed = colony.inventory().snapshot().residents().get(body.id());
                helper.assertTrue(observed.pack().stream().noneMatch(slot -> slot.stackCopy().is(backpack.getItem())),
                    "the observed pack excludes the worn backpack item");
                helper.assertTrue(store.countItem(backpack.getItem()) == 1, "one physical backpack left colony stock");
                var restored = PersonBody.RESIDENT.get().create(level);
                try {
                    var saved = body.saveWithoutId(new CompoundTag());
                    saved.putUUID("UUID", UUID.randomUUID());
                    restored.load(saved);
                    helper.assertTrue(java.util.Objects.equals(
                        GearSlots.slot(rootSlot).orElseThrow().worn(restored).get(DataComponents.CUSTOM_DATA),
                        expected.get(DataComponents.CUSTOM_DATA)), "worn item round trips with opaque components");
                } finally {
                    restored.discard();
                }
                if (!disc.isEmpty()) {
                    helper.assertTrue(store.countItem(disc.getItem()) == 0
                        && EquipmentKits.parts(body, profiles.assigned(body.id()).orElseThrow()).values().stream()
                            .flatMap(List::stream).anyMatch(part -> part.stack().is(disc.getItem())),
                        "a real configured disc left registered stock for native installation");
                }
                ready.accept(body);
                if (crafts) {
                    body.pack().insert(new ItemStack(Items.OAK_PLANKS, 2));
                    var delivery = Delivery.to(UUID.randomUUID(), owner, WorkSite.on(body), Stances.WHEREVER,
                        ItemSpec.of(Items.STICK), 4, count -> made.addAndGet((int) count));
                    colony.submit(owner, level.dimension(), Grown.of(delivery), (id, ending) -> { });
                } else {
                    made.set(4);
                }
            }).thenWaitUntil(() -> helper.assertTrue(made.get() == 4, "planned recipe uses the bearer's pack: "
                + labor.diagnosis() + " route=" + labor.route(body)))
            .thenExecute(() -> {
                if (crafts) {
                    helper.assertTrue(offered.get(), "normal planning offered a carried recipe");
                    helper.assertTrue(body.blockPosition().equals(start), "crafting never walked to a station");
                }
                // Keep the worn root and remove its configured children: native extraction returns each real item.
                profiles.replace(profiles.get(profile.id()).orElseThrow().with(List.of(parent)));
            }).thenWaitUntil(() -> helper.assertTrue(
                (upgrade.isEmpty() || store.countItem(upgrade.getItem()) == 1)
                    && (disc.isEmpty() || store.countItem(disc.getItem()) == 1),
                "removed upgrades and discs return through normal hauling: " + labor.diagnosis()))
            .thenExecute(() -> profiles.assign(List.of(body.id()), Optional.empty()))
            .thenWaitUntil(() -> helper.assertTrue(GearSlots.slot(rootSlot).orElseThrow().worn(body).isEmpty()
                && body.pack().count(backpack.getItem()) == 0 && store.countItem(backpack.getItem()) == 2,
                "unassignment returns both original backpacks without loss: " + labor.diagnosis()))
            .thenExecute(() -> {
                helper.assertTrue(KitProviders.capabilities(body).equals(KitCapabilities.NONE)
                    && body.pack().plannableCells(item -> false) == 18, "returned gear no longer grants capacity");
                helper.assertTrue(java.util.stream.IntStream.range(0, store.getContainerSize())
                    .mapToObj(store::getItem).filter(stack -> stack.is(backpack.getItem()))
                    .allMatch(stack -> java.util.Objects.equals(stack.get(DataComponents.CUSTOM_DATA),
                        expected.get(DataComponents.CUSTOM_DATA))),
                    "backpack payload is unchanged after return, independent of the chosen storage cell");
            }).thenSucceed();
    }
}
