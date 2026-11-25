package io.github.izakyl.folkways.core.shell.cbc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.assistant.sandbox.Probe;
import io.github.izakyl.folkways.assistant.sandbox.Sandbox;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.card.net.CardOrders;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.shell.cbc.mixin.CannonMountAccess;
import io.github.izakyl.folkways.core.shell.letitwalk.LetItWalkGameTests;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.ShootRoutine;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

@PrefixGameTestTemplate(false)
public final class CbcWalkerGameTests {

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 800,
            batch = "calibrated_walker_cannon")
    public static void probeAimsCannonOnCopiedWalker(GameTestHelper helper) {
        var level = helper.getLevel();
        var mount = new AtomicReference<CannonMountBlockEntity>();
        Cleanup.on(helper, () -> {
            if (mount.get() != null) {
                mount.get().disassemble();
            }
        });
        var walker = LetItWalkGameTests.Walker.build(helper, new BlockPos(1664, 100, 64));
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "probe-test"),
            ClientInformation.createDefault());
        var probe = new AtomicReference<CompletableFuture<JsonObject>>();
        double[] real = new double[2];
        helper.startSequence().thenExecuteAfter(80, () -> {
            walker.settled(helper);
            var controls = walker.controls();
            var mountCell = controls.at(controls.cell().east(2));
            var stored = WorldSpaces.storage(level, mountCell).orElseThrow();
            mount.set(CbcGameTests.crankDrivenBronzeMount(level, stored));
            level.setBlockAndUpdate(stored.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        }).thenExecuteAfter(20, () -> {
            var cannon = mount.get();
            helper.assertTrue(CbcCannon.mounted(cannon).isPresent(), "cannon assembled on the walker");
            var angles = (CannonMountAccess) cannon;
            real[0] = angles.folkwaysYaw();
            real[1] = angles.folkwaysPitch();
            var scan = MechanismScan.structure(level, walker.hull());
            var aim = new Probe.Input.Write(WorldSpaces.at(level, cannon.getBlockPos()), "aim",
                Optional.of(Value.of(real[0] + 30, 10)));
            probe.set(Sandbox.probe(player, new Probe.Request(scan, aim, 100)));
        }).thenWaitUntil(AsyncWait.until(helper, () -> probe.get().isDone(), "walker cannon aim probe"))
            .thenExecute(() -> {
                var report = probe.get().join();
                CbcGameTests.assertAim(helper, report, real[0] + 30, 10);
                helper.assertTrue(report.getAsJsonArray("structures").size() > 1
                    && report.getAsJsonArray("structures").toString().contains(walker.hull().toString()),
                    "the probe copied the walker hull and its legs: " + report);
                var angles = (CannonMountAccess) mount.get();
                helper.assertTrue(angles.folkwaysYaw() == real[0] && angles.folkwaysPitch() == real[1],
                    "the real walker cannon kept its aim");
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 1600,
            batch = "resident_walker_cannon")
    public static void residentFiresCannonOnWalker(GameTestHelper helper) {
        var level = helper.getLevel();
        var pad = new BlockPos(1792, 100, 64);
        var walker = LetItWalkGameTests.Walker.build(helper, pad);
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "walker-keeper"),
            ClientInformation.createDefault());
        var colony = Charters.found(player, "Walker cannon");
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var services = DefinedFacilities.of(colony).orElseThrow();
        var labor = new LaborFixture(colony, level);
        var resident = PersonBody.RESIDENT.get().create(level);
        var mount = new AtomicReference<CannonMountBlockEntity>();
        var shot = new AtomicReference<AbstractBigCannonProjectile>();
        Consumer<EntityJoinLevelEvent> collect = event -> {
            if (event.getLevel() == level && event.getEntity() instanceof AbstractBigCannonProjectile projectile
                    && mount.get() != null && (projectile.position().distanceToSqr(pad.getCenter()) < 32 * 32
                        || projectile.position().distanceToSqr(mount.get().getBlockPos().getCenter()) < 12 * 12)) {
                shot.set(projectile);
            }
        };
        NeoForge.EVENT_BUS.addListener(collect);
        Cleanup.on(helper, () -> {
            NeoForge.EVENT_BUS.unregister(collect);
            if (shot.get() != null) {
                shot.get().discard();
            }
            if (mount.get() != null) {
                mount.get().disassemble();
            }
            labor.close();
            resident.discard();
            colony.raze();
        });

        var host = new ScriptHost();
        var definition = new AtomicReference<CompletableFuture<Void>>();
        helper.startSequence().thenExecuteAfter(80, () -> {
            walker.settled(helper);
            var controls = walker.controls();
            var stored = WorldSpaces.storage(level, controls.at(controls.cell().east(2))).orElseThrow();
            mount.set(CbcGameTests.crankDrivenBronzeMount(level, stored));
            level.setBlockAndUpdate(stored.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            var stock = controls.at(controls.cell().west(2));
            var barrel = WorldSpaces.storage(level, stock).orElseThrow();
            level.setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
            var store = Containers.at(level, barrel).orElseThrow();
            store.setItem(0, new ItemStack(CbcGameTests.block("solid_shot")));
            store.setItem(1, new ItemStack(CbcGameTests.block("powder_charge")));
            colony.stores(CbcIntegration.id("walker_supply"), level.dimension(), List.of(stock));
            var deck = controls.at(controls.cell().west());
            resident.moveTo(WorldSpaces.world(level, deck).orElseThrow().add(0, .02, 0));
            resident.setOnGround(true);
            level.addFreshEntity(resident);
            colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
            resident.joinColony(colony.id());
            colony.entered(resident);
            helper.onEachTick(labor::tick);
        }).thenExecuteAfter(20, () -> {
            helper.assertTrue(CbcCannon.mounted(mount.get()).isPresent(), "cannon assembled on the walker");
            var anchor = walker.controls().cell();
            var hull = FacilityExtent.structure(level, walker.hull()).orElseThrow();
            var extent = FacilityExtent.blocks(hull.cells().stream()
                .filter(cell -> !level.getBlockState(cell.block(level)).isAir()).toList());
            helper.assertTrue(extent.contains(walker.controls().at(anchor.west(2))), "hull includes the supply barrel");
            definition.set(CbcGameTests.approve(helper, host, player, """
                set("facilities/walker/definition", {
                    "name": "walker",
                    "extent": %s,
                    "anchor": [%d, %d, %d],
                    "controls": {
                        "destination": {"part": "destination", "offset": [0, 0, 0]},
                        "aim": {"part": "aim", "offset": [2, 0, 0]},
                        "fire": {"part": "fire", "offset": [2, 0, 0]},
                        "breech": {"part": "breech", "offset": [2, 0, 0]}},
                    "parameters": {"shell": "createbigcannons:solid_shot", "range": 96},
                    "routines": {
                        "walk": {"use": "folkways_core:move_to"},
                        "shoot": {"source": %s, "with": {
                            "aim": "aim", "fire": "fire", "breech": "breech",
                            "ammunition": [[{"param": "shell"}, 1], ["createbigcannons:powder_charge", 1]]}}},
                    "triggers": {"walk": {"kind": "manual", "routine": "walk"},
                                 "shoot": {"kind": "manual", "routine": "shoot"}},
                    "ui": {"card": [{"target": "walk", "pick": "pos", "default": True},
                                    {"target": "shoot", "pick": "entity|pos"}]}})
                """.formatted(new Gson().toJson(DefinitionCodec.extent(extent)),
                    anchor.getX(), anchor.getY(), anchor.getZ(), ShootRoutine.literal())));
        }).thenWaitUntil(AsyncWait.until(helper, () -> definition.get().isDone(), "walker cannon definition"))
            .thenExecute(() -> {
                definition.get().join();
                helper.assertTrue(WorldSpaces.at(resident).sameRealm(walker.controls()), "the gunner is aboard");
                var origin = WorldSpaces.world(level, WorldSpaces.at(level, mount.get().getBlockPos())).orElseThrow();
                var target = BlockPos.containing(origin).offset(18, 0, 25).atY(pad.getY());
                var operating = Operations.find(colony, services.facilities().getFirst().id()).orElseThrow();
                var refusal = CardOrders.apply(player, operating,
                    new CardOrders.Press.Pick(1, "shoot", Value.of(target.getX(), target.getY(), target.getZ())));
                helper.assertTrue(refusal.isEmpty(), "walker shot submitted: " + refusal);
            }).thenWaitUntil(() -> helper.assertTrue(shot.get() != null,
                "waiting for walker projectile: " + services.recent("walker") + " running " + services.running("walker")
                    + " resident at " + resident.position()))
            .thenExecute(() -> {
                var controls = walker.controls();
                var stock = controls.at(controls.cell().west(2));
                var barrel = Containers.at(level, stock.block(level)).orElseThrow();
                helper.assertTrue(barrel.isEmpty(), "the gunner used both rounds from the deck barrel");
            }).thenSucceed();
    }
}
