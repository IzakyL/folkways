package io.github.izakyl.folkways.vanilla.patrol;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.resident.api.Locomotions;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.resident.api.ResidentKind;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.combat.weapon.Weapons;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Representative independent Worker/ResidentKind implementations; no Modular Golems binary dependency. */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class LegacyToolStrikeGameTests {
    private static final ResourceLocation HUMANOID =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_legacy_humanoid_patroller");
    private static final ResourceLocation BAREHANDED =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_legacy_barehand_patroller");

    @SubscribeEvent
    public static void declareFixtures(Declaring event) {
        event.residentKind(ResidentKind.growing(HUMANOID, Locomotions.named(Locomotions.WALKING)));
        event.residentKind(
            ResidentKind.growing(BAREHANDED, Locomotions.named(Locomotions.WALKING))
            .barehanded(Set.of(PatrolContent.VOCATION)));
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LegacyToolStrikeGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void anIndependentArmedHumanoidRetainsTheToolUseMeleePath(GameTestHelper helper) {
        Scene scene = scene(helper, false);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamageValue(7);
        scene.pack().setItem(0, new ItemStack(Items.BOW));
        scene.pack().setItem(1, new ItemStack(Items.ARROW, 3));
        scene.pack().setItem(2, sword);
        helper.assertTrue(
            Weapons.armed(scene.worker()), "an independently implemented humanoid is armed by its held melee tool");
        helper.assertFalse(
            Weapons.ranged(scene.worker()),
            "an unsupported body keeps its previous melee capability despite carrying a bow");
        helper.assertValueEqual(Weapons.range(scene.worker()), 4.5, "legacy melee reach remains unchanged");
        helper.assertTrue(
            Weapons.open(scene.worker()).isEmpty(),
            "legacy combat does not claim an unsupported native hand session");
        Node strike = strike(helper, scene);
        helper.assertTrue(
            strike instanceof ToolStrikeNode, "patrol dispatches non-person bodies to the legacy tool node");
        helper.assertTrue(
            Tools.equipped(scene.worker().resident().kind(), strike.spec(), List.of(sword)),
            "the humanoid answers the declared tool requirement");
        // The legacy Core runner presents a copy while its actual tool remains in the pack.
        scene.body().setItemInHand(InteractionHand.MAIN_HAND, sword.copy());
        helper.runAfterDelay(
            2,
            () -> {
                try {
                    float before = scene.target().getHealth();
                    strike.commit(helper.getLevel(), scene.worker());
                    helper.assertTrue(
                        scene.target().getHealth() < before,
                        "the existing native melee path actually damages the target");
                    helper.assertTrue(
                        scene.pack().getItem(2) == sword && sword.getDamageValue() == 7,
                        "the node leaves actual pack ownership and tool wear to Core rather than creating a native loan");
                } finally {
                    scene.close();
                }
                helper.succeed();
            });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aBarehandedIndependentKindStillFightsWhileCarryingASword(GameTestHelper helper) {
        Scene scene = scene(helper, true);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamageValue(11);
        scene.pack().setItem(0, sword);
        helper.assertTrue(Weapons.armed(scene.worker()), "a barehanded worker remains armed while carrying a sword");
        helper.assertTrue(
            Weapons.open(scene.worker()).isEmpty(),
            "a carried sword cannot divert a barehanded golem into an unavailable adapter");
        helper.assertFalse(Weapons.ranged(scene.worker()), "a barehanded independent kind retains melee targeting");
        Node strike = strike(helper, scene);
        helper.assertTrue(
            strike instanceof ToolStrikeNode, "a sword-carrying barehanded kind still takes the legacy path");
        helper.assertTrue(
            Tools.equipped(scene.worker().resident().kind(), strike.spec(), List.of()),
            "a barehanded kind's own body answers the declared tool requirement");
        helper.runAfterDelay(
            2,
            () -> {
                try {
                    float before = scene.target().getHealth();
                    strike.commit(helper.getLevel(), scene.worker());
                    helper.assertTrue(
                        scene.target().getHealth() < before,
                        "the barehanded native blow actually damages the target");
                    helper.assertTrue(
                        scene.body().getMainHandItem().isEmpty(),
                        "Core does not present a tool for a barehanded kind");
                    helper.assertTrue(
                        scene.pack().getItem(0) == sword && sword.getDamageValue() == 11,
                        "the carried sword remains owned and untouched");
                    scene.pack().setItem(0, ItemStack.EMPTY);
                    helper.assertTrue(
                        Weapons.armed(scene.worker()), "the kind also stays armed without a carried sword");
                } finally {
                    scene.close();
                }
                helper.succeed();
            });
    }

    private static Node strike(GameTestHelper helper, Scene scene) {
        PatrolPresence patrol = new PatrolPresence(scene.colony());
        patrol.watched(scene.body().getUUID(), helper.getLevel().getGameTime());
        var urges = patrol.urges(scene.worker(), scene.colony().view(helper.getLevel()));
        helper.assertValueEqual(urges.size(), 1, "legacy worker receives an actual patrol combat urge");
        return urges.getFirst().take().get();
    }

    private static Scene scene(GameTestHelper helper, boolean barehanded) {
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 3; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        var id = barehanded ? BAREHANDED : HUMANOID;
        ResidentKinds.of(id)
            .orElseThrow(() -> new IllegalStateException("legacy test kind was not declared during Declaring"));
        Mob body = EntityType.VINDICATOR.create(helper.getLevel());
        Zombie target = EntityType.ZOMBIE.create(helper.getLevel());
        body.setNoAi(true);
        target.setNoAi(true);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        body.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        target.moveTo(at.getX() + 1.5, at.getY(), at.getZ() + .5);
        helper.getLevel().addFreshEntity(body);
        helper.getLevel().addFreshEntity(target);
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        SimpleContainer pack = new SimpleContainer(3);
        Worker worker =
            TestWorker.of(body, new Resident(body.getUUID(), id), pack).heldFromPack();
        return new Scene(colony, body, target, pack, worker);
    }

    private record Scene(Colony colony, Mob body, Zombie target, SimpleContainer pack, Worker worker) {
        void close() {
            body.discard();
            target.discard();
            colony.raze();
        }
    }
}
