package io.github.izakyl.folkways.core.shell.cbc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.kinetics.crank.HandCrankBlock;
import com.simibubi.create.content.kinetics.crank.HandCrankBlockEntity;
import io.github.izakyl.folkways.assistant.sandbox.Sandbox;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ActionTools;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.assistant.tool.internal.Surveys;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.combat.api.FireControl;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.shell.cbc.mixin.CannonMountAccess;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.ShootRoutine;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountInterfaceBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.breeches.BigCannonBreechStrengthHandler;
import rbasamoyai.createbigcannons.index.CBCDataComponents;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonCartridgeItem;
import rbasamoyai.createbigcannons.munitions.autocannon.ammo_container.AutocannonAmmoContainerItem;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCartridgeBlockItem;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class CbcGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CbcGameTests.class);
        if (ModList.get().isLoaded("sable")) {
            event.register(CbcSableGameTests.class);
        }
        if (ModList.get().isLoaded("let_it_walk")) {
            event.register(CbcWalkerGameTests.class);
        }
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID)
    public static void mountAimLoadAndFire(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = new BlockPos(4, 1, 4);
        var absolute = helper.absolutePos(pos);
        var anchor = WorldSpaces.at(level, absolute);
        var mount = mount(level, absolute);
        var writer = helper.spawn(EntityType.PIG, pos.south());
        var controls = Controls.within(level, List.of(anchor));
        helper.assertTrue(controls.stream().map(Control::part).toList().containsAll(List.of("aim", "fire", "breech")),
            "area discovery finds the three cannon controls");
        helper.assertTrue(controls.stream().filter(control -> List.of("aim", "fire", "breech").contains(control.part()))
            .allMatch(control -> control.anchor().equals(anchor) && control.leaseKey().equals(anchor)),
            "cannon controls share the mount's anchor and lease");
        helper.assertTrue(read(helper, anchor, "assembled").equals(Value.of(false)), "starts disassembled");
        helper.setBlock(pos.west(), Blocks.REDSTONE_BLOCK);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(read(helper, anchor, "assembled").equals(Value.of(true)), "mount assembled the cannon");
            var aim = Controls.signal(level, anchor, "aim").orElseThrow();
            var angles = Value.of(-85, 5);
            helper.assertTrue(aim.write(level, writer, angles) == Signal.Write.WRITTEN, "aim accepted");
            helper.assertTrue(aim.write(level, writer, Value.of(0, 91)) == Signal.Write.REFUSED,
                "aim outside the native elevation range is refused");
            var cannon = CbcCannon.mounted(mount).orElseThrow();
            var breech = cannon.quickBreech();
            helper.assertTrue(breech != null, "quick-firing breech assembled");
            breech.toggleOpening();
            helper.runAfterDelay(40, () -> {
                helper.assertTrue(aim.commandFeedback().orElseThrow().read(level).orElseThrow().equals(angles),
                    "both shafts reached the commanded angle and came to rest");
                helper.assertTrue(Math.abs(Mth.wrapDegrees(cannon.entity().yaw + 85)) < .02
                    && Math.abs(Math.abs(cannon.entity().pitch) - 5) < .02, "the assembled contraption also turned");
                var port = Ports.at(level, anchor, ResourceKind.ITEM, "breech").orElseThrow();
                var shell = new ItemStack(block("solid_shot"));
                var powder = new ItemStack(block("powder_charge"));
                helper.assertTrue(port.deposit(level, writer, powder, false).isEmpty(), "charge first is refused");
                helper.assertTrue(port.deposit(level, writer, shell, true).getCount() == 1,
                    "shell simulation finds room");
                helper.assertTrue(port.deposit(level, writer, powder, false).isEmpty(),
                    "simulation left the bore empty");
                helper.assertTrue(port.deposit(level, writer, shell, false).getCount() == 1, "shell accepted");
                helper.assertTrue(port.deposit(level, writer, shell, false).isEmpty(), "second shell is refused");
                helper.assertTrue(port.deposit(level, writer, powder, false).getCount() == 1,
                    "charge accepted after shell");
                helper.assertTrue(read(helper, anchor, "loaded").equals(Value.of(true)), "loaded reading");
                breech.toggleOpening();
                helper.runAfterDelay(20, () -> {
                    var fire = Controls.signal(level, anchor, "fire").orElseThrow();
                    helper.assertTrue(fire.write(level, writer, Value.PULSE) == Signal.Write.WRITTEN,
                        "facility pulse fired the loaded native cannon");
                    helper.assertTrue(read(helper, anchor, "loaded").equals(Value.of(false)),
                        "native shot consumed load");
                    var projectiles = level.getEntitiesOfClass(AbstractBigCannonProjectile.class,
                        cannon.entity().getBoundingBox().inflate(10));
                    helper.assertTrue(!projectiles.isEmpty(), "native CBC projectile spawned");
                    projectiles.forEach(AbstractBigCannonProjectile::discard);
                    mount.disassemble();
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 1200,
            batch = "calibrated_land_cannon")
    public static void calibrateAndFireLandCannon(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        var target = prepareRange(helper, pos).add(0, 0, 3);
        var mount = mount(level, pos);
        var anchor = WorldSpaces.at(level, pos);
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "cannon-keeper"),
            ClientInformation.createDefault());
        var colony = Charters.found(player, "Cannon calibration");
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        Cleanup.on(helper, () -> {
            mount.disassemble();
            colony.raze();
        });
        confirmSurvey(helper, player, pos.offset(-12, -2, -12), pos.offset(12, 6, 12));
        var host = new ScriptHost();
        var request = new AtomicReference<CompletableFuture<ToolReply>>();
        var projectileSpawned = new AtomicBoolean();
        var watching = new AtomicBoolean(true);
        var box = new AABB(pos).inflate(12);
        helper.onEachTick(() -> {
            if (watching.get() && (!level.getEntitiesOfClass(AbstractBigCannonProjectile.class, box).isEmpty()
                    || Sandbox.level(level.getServer()).filter(copy ->
                        !copy.getEntitiesOfClass(AbstractBigCannonProjectile.class, box).isEmpty()).isPresent())) {
                projectileSpawned.set(true);
            }
        });
        String scope = """
            at = {"dimension": "%s", "x": %d, "y": %d, "z": %d}
            control = {"x": at["x"], "y": at["y"], "z": at["z"], "part": "%s"}
            """;
        String dimension = level.dimension().location().toString();
        level.setBlockAndUpdate(pos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence().thenExecuteAfter(3, () -> request.set(host.run(player,
            scope.formatted(dimension, pos.getX(), pos.getY(), pos.getZ(), "aim") + """
            graph = mechanism(at=at)
            mount = [n for n in graph["nodes"] if n["kind"] == "cannon_mount"][0]
            plus = probe(at=at, control=control, value=[mount["yaw"] + 30, 10], ticks=100)
            minus = probe(at=at, control=control, value=[mount["yaw"] - 30, 5], ticks=100)
            result = {"graph": graph, "plus": plus, "minus": minus}
            """, new JsonObject())))
            .thenWaitUntil(AsyncWait.until(helper, () -> request.get().isDone(), "mechanism and aim probes"))
            .thenExecute(() -> {
                var result = scriptData(helper, request.get().join()).getAsJsonObject("result");
                var graph = result.getAsJsonObject("graph");
                var node = graph.getAsJsonArray("nodes").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(row -> row.get("kind").getAsString().equals("cannon_mount")).findFirst().orElseThrow();
                var barrel = graph.getAsJsonArray("nodes").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(row -> row.get("kind").getAsString().equals("cannon")).findFirst().orElseThrow();
                helper.assertTrue(roles(node).containsAll(List.of("control", "actuator"))
                    && node.has("yaw") && node.has("pitch") && node.has("loaded") && !node.get("loaded").getAsBoolean()
                    && roles(barrel).contains("moving"), "mount and moving barrel appear in mechanism: " + graph);
                helper.assertTrue(graph.getAsJsonArray("edges").asList().stream().map(JsonElement::getAsJsonObject)
                    .anyMatch(edge -> edge.get("link").getAsString().equals("moves")
                        && edge.get("from").equals(node.get("id")) && edge.get("to").equals(barrel.get("id"))),
                    "the mount moves the barrel: " + graph);
                double yaw = node.get("yaw").getAsDouble();
                double pitch = node.get("pitch").getAsDouble();
                assertAim(helper, result.getAsJsonObject("plus"), yaw + 30, 10);
                assertAim(helper, result.getAsJsonObject("minus"), yaw - 30, 5);
                helper.assertTrue(read(helper, anchor, "aim").equals(Value.of(yaw, pitch)),
                    "neither aim probe turned the real cannon");

                var cannon = CbcCannon.mounted(mount).orElseThrow();
                var charge = cannon.cannon().getStartPos();
                var shell = charge.relative(cannon.cannon().initialOrientation());
                loadProbeRound(level, cannon, charge, "powder_charge");
                loadProbeRound(level, cannon, shell, "solid_shot");
                BigCannonBlock.writeAndSyncMultipleBlockData(Set.of(charge, shell), cannon.entity(), cannon.cannon());
                helper.assertTrue(cannon.loaded(), "fire probe starts with a real load to copy");
                request.set(host.run(player, scope.formatted(dimension, pos.getX(), pos.getY(), pos.getZ(), "fire")
                    + "result = probe(at=at, control=control, ticks=20)", new JsonObject()));
            }).thenWaitUntil(AsyncWait.until(helper, () -> request.get().isDone(), "fire probe"))
            .thenExecute(() -> {
                var probe = scriptData(helper, request.get().join()).getAsJsonObject("result");
                assertWritten(helper, probe);
                helper.assertTrue(probe.getAsJsonObject("suppressed").entrySet().stream().anyMatch(entry ->
                    entry.getKey().startsWith("createbigcannons:") && entry.getKey().contains("(projectile,")
                        && entry.getValue().getAsInt() == 1), "the CBC projectile was suppressed: " + probe);
                helper.assertFalse(projectileSpawned.get(), "neither the world nor the sandbox spawned a projectile");
                var sandbox = Sandbox.level(level.getServer()).orElseThrow();
                helper.assertTrue(sandbox.getEntitiesOfClass(AbstractBigCannonProjectile.class, box).isEmpty()
                    && !Sandbox.busy(), "the completed probe left no sandbox projectile");
                watching.set(false);

                var cannon = CbcCannon.mounted(mount).orElseThrow();
                helper.assertTrue(cannon.loaded(), "the fire probe left the real load alone");
                var charge = cannon.cannon().getStartPos();
                var shell = charge.relative(cannon.cannon().initialOrientation());
                cannon.chamber(charge).removeBlock();
                cannon.chamber(shell).removeBlock();
                BigCannonBlock.writeAndSyncMultipleBlockData(Set.of(charge, shell), cannon.entity(), cannon.cannon());

                residentFires(helper, mount, target, colony, (name, data) -> host.run(player,
                    "set(\"facilities/" + name + "/definition\", " + new Gson().toJson(data) + ")",
                    new JsonObject()).thenCompose(preview -> host.decide(player,
                        scriptData(helper, preview).get("revision").getAsString(), true))
                    .thenAccept(reply -> scriptData(helper, reply)));
            });
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 800)
    public static void assistantCannonRejectsSquibLoad(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        prepareRange(helper, pos);
        var mount = mount(level, pos, 3);
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "cannon-keeper"),
            ClientInformation.createDefault());
        var colony = Charters.found(player, "Assistant cannon");
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        Cleanup.on(helper, () -> {
            mount.disassemble();
            colony.raze();
        });
        var host = new ScriptHost();
        var target = Vec3.atLowerCornerOf(pos.offset(44, 4, 6));
        String xyz = "[%d, %d, %d]".formatted(pos.getX(), pos.getY(), pos.getZ());
        String definition = """
            set("facilities/cannon/definition", {
                "name": "cannon",
                "extent": {"elements": [{"space": {"dimension": "%s"}, "min": %s, "max": %s}]},
                "anchor": %s,
                "controls": {"aim": {"part": "aim", "offset": [0, 0, 0]},
                             "fire": {"part": "fire", "offset": [0, 0, 0]},
                             "breech": {"part": "breech", "offset": [0, 0, 0]}},
                "parameters": {"shell": "createbigcannons:solid_shot", "range": 96},
                "routines": {"shoot": {"source": %s, "with": {
                    "aim": "aim", "fire": "fire", "breech": "breech",
                    "ammunition": [[{"param": "shell"}, 1], ["createbigcannons:powder_charge", 1]]}}},
                "readings": {"nearby": {"probe": "folkways_vanilla:hostile_within", "range": 48}},
                "triggers": {"auto": {"kind": "condition", "routine": "shoot",
                                      "source": "folkways_core:fire_control", "enabled": True, "cooldown": 120},
                             "manual": {"kind": "manual", "routine": "shoot"}},
                "ui": {"card": [{"target": "shoot", "pick": "entity|pos", "default": True, "icon": "minecraft:target"},
                                {"choice": "shell",
                                 "options": ["createbigcannons:solid_shot", "createbigcannons:he_shell"],
                                 "labels": ["solid", "he"],
                                 "icons": ["createbigcannons:solid_shot", "createbigcannons:he_shell"]},
                                {"number": "range", "min": 32, "max": 160, "step": 16, "label": "range"}],
                       "overlay": [{"range": "nearby"}, {"path": "shoot"}]}})
            """.formatted(level.dimension().location(), xyz, xyz, xyz, ShootRoutine.literal());
        String run = """
            set("facilities/cannon/runs/shoot", {"routine": "shoot", "args": {"target": [%d, %d, %d]}})
            """.formatted((int) target.x, (int) target.y, (int) target.z);
        var pending = new AtomicReference<CompletableFuture<Void>>();
        var services = DefinedFacilities.of(colony).orElseThrow();
        level.setBlockAndUpdate(pos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence().thenExecuteAfter(3, () -> pending.set(approve(helper, host, player, definition)))
            .thenWaitUntil(AsyncWait.until(helper, () -> pending.get().isDone(), "long cannon definition"))
            .thenExecute(() -> {
                pending.get().join();
                pending.set(approve(helper, host, player, run));
            })
            .thenWaitUntil(AsyncWait.until(helper, () -> pending.get().isDone(), "long cannon run"))
            .thenWaitUntil(() -> helper.assertTrue(services.recent("cannon").flatMap(result -> result.reason())
                    .filter(reason -> reason.contains("insufficient charge power")).isPresent(),
                "the shot is refused with the reason the shell could lodge: " + services.recent("cannon")))
            .thenExecute(() -> {
                pending.get().join();
                level.setBlockAndUpdate(pos.west(), Blocks.AIR.defaultBlockState());
                mount.disassemble();
                level.setBlockAndUpdate(pos.above(2).east(3), Blocks.AIR.defaultBlockState());
                residentFires(helper, mount(level, pos), target, colony, "cannon",
                    () -> approve(helper, host, player, definition), () -> approve(helper, host, player, run), false);
            });
    }

    static CompletableFuture<Void> approve(GameTestHelper helper, ScriptHost host, ServerPlayer player,
                                           String script) {
        return host.run(player, script, new JsonObject()).thenCompose(preview -> host.decide(player,
            scriptData(helper, preview).get("revision").getAsString(), true))
            .thenAccept(reply -> scriptData(helper, reply));
    }

    private static void loadProbeRound(ServerLevel level, CbcCannon cannon, BlockPos cell, String item) {
        var munition = (BigCannonMunitionBlock) block(item);
        cannon.chamber(cell).loadBlock(munition.getHandloadingInfo(new ItemStack(block(item)), cell,
            cannon.cannon().initialOrientation(), level.registryAccess()));
    }

    private static List<String> roles(JsonObject node) {
        return node.getAsJsonArray("roles").asList().stream().map(JsonElement::getAsString).toList();
    }

    /** Mechanism and probe reads are limited to the player's confirmed survey, as in production. */
    private static void confirmSurvey(GameTestHelper helper, ServerPlayer player, BlockPos from, BlockPos to) {
        Cleanup.on(helper, () -> Surveys.forget(player));
        JsonObject args = new JsonObject();
        args.addProperty("dimension", player.serverLevel().dimension().location().toString());
        JsonArray points = new JsonArray();
        for (BlockPos pos : new BlockPos[] {from, to}) {
            JsonObject point = new JsonObject();
            point.addProperty("x", pos.getX());
            point.addProperty("y", pos.getY());
            point.addProperty("z", pos.getZ());
            points.add(point);
        }
        args.add("points", points);
        var actions = ActionTools.create();
        var preview = actions.preview(player, "request_survey", args).data();
        helper.assertTrue(preview.has("revision"), "survey preview succeeds: " + preview);
        var result = AsyncWait.result(helper, actions.apply(player, "request_survey", args,
            preview.get("revision").getAsString())).data();
        helper.assertFalse(result.has("error"), "survey confirmation succeeds: " + result);
    }

    private static JsonObject scriptData(GameTestHelper helper, ToolReply reply) {
        helper.assertFalse(reply.data().has("error"), "script succeeds: " + reply.data());
        return reply.data();
    }

    private static void assertWritten(GameTestHelper helper, JsonObject probe) {
        helper.assertTrue(!probe.has("error")
            && probe.getAsJsonObject("input").get("result").getAsString().equals("written"),
            "copied cannon took the input: " + probe);
    }

    static void assertAim(GameTestHelper helper, JsonObject probe, double yaw, double pitch) {
        assertWritten(helper, probe);
        var after = probe.getAsJsonArray("changes").asList().stream().map(JsonElement::getAsJsonObject)
            .filter(change -> change.get("id").getAsString().startsWith("cannon_mount@")).map(change -> change.getAsJsonObject("after"))
            .findFirst().orElse(null);
        helper.assertTrue(after != null && Math.abs(after.get("yaw").getAsDouble() - yaw) < .5
            && Math.abs(after.get("pitch").getAsDouble() - pitch) < .5, "copied mount reached aim: " + probe);
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 800)
    public static void residentLoadsCannonAndHitsTarget(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        var target = prepareRange(helper, pos);
        residentFires(helper, mount(helper.getLevel(), pos), target);
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 500)
    public static void staffedCannonLoadsAndFiresOnTime(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        var footing = pos.south();
        level.setBlockAndUpdate(footing.below(), Blocks.OBSIDIAN.defaultBlockState());
        var mount = mount(level, pos);
        var anchor = WorldSpaces.at(level, pos);
        var colony = Colonies.mint(level.getServer());
        var resident = PersonBody.RESIDENT.get().create(level);
        resident.moveTo(footing.getBottomCenter());
        resident.setOnGround(true);
        level.addFreshEntity(resident);
        colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(colony.id());
        colony.entered(resident);
        resident.pack().insert(new ItemStack(block("solid_shot")));
        resident.pack().insert(new ItemStack(block("powder_charge")));
        var labor = new LaborFixture(colony, level);
        helper.onEachTick(labor::tick);
        var shotArea = new AABB(pos).inflate(8);
        long[] when = {-1, -1, -1};
        var fired = new AtomicBoolean();
        Consumer<EntityJoinLevelEvent> observe = event -> {
            if (event.getLevel() == level && event.getEntity() instanceof AbstractBigCannonProjectile projectile
                    && shotArea.contains(projectile.position())) {
                fired.set(true);
                Cleanup.on(helper, projectile::discard);
            }
        };
        NeoForge.EVENT_BUS.addListener(observe);
        Cleanup.on(helper, () -> {
            NeoForge.EVENT_BUS.unregister(observe);
            mount.disassemble();
            labor.close();
            resident.discard();
            colony.raze();
        });
        helper.onEachTick(() -> {
            Ports.at(level, anchor, ResourceKind.ITEM, "breech").ifPresent(port -> {
                int size = port.contents(level).size();
                if (size > 0 && when[size - 1] < 0) {
                    when[size - 1] = level.getGameTime();
                }
            });
            if (fired.get() && when[2] < 0) {
                when[2] = level.getGameTime();
            }
        });

        var services = DefinedFacilities.of(colony).orElseThrow();
        String name = "Timed cannon";
        level.setBlockAndUpdate(pos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        long[] submitted = {-1};
        helper.startSequence().thenExecuteAfter(3, () -> {
            var extent = FacilityExtent.blocks(List.of(anchor, WorldSpaces.at(level, footing)));
            var data = Map.<String, Object>of("name", name, "extent", DefinitionCodec.extent(extent),
                "anchor", List.of(pos.getX(), pos.getY(), pos.getZ()),
                "controls", Map.of("fire", Map.of("part", "fire", "offset", List.of(0, 0, 0)),
                    "breech", Map.of("part", "breech", "offset", List.of(0, 0, 0))),
                "posts", Map.of("loader", Map.of("stance", Map.of("footing", List.of(0, 0, 1)))),
                "routines", Map.of("shoot", Map.of("use", "folkways_core:write_sequence", "with",
                    Map.of("steps", List.of(
                        Map.of("into", "breech", "item", "createbigcannons:solid_shot", "count", 1, "atTick", 0),
                        Map.of("into", "breech", "item", "createbigcannons:powder_charge", "count", 1, "atTick", 8),
                        Map.of("control", "fire", "value", Map.of("pulse", true), "atTick", 48))))),
                "triggers", Map.of("order", Map.of("kind", "manual", "routine", "shoot")));
            var preview = services.preview(name, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "timed cannon validates: " + preview.problems());
            helper.assertTrue(services.apply(preview).isEmpty(), "timed cannon installed");
        }).thenWaitUntil(() -> {
            var facility = services.facilities().stream().filter(one -> name.equals(one.name())).findFirst();
            helper.assertTrue(facility.flatMap(one -> services.posts().coveringPost(level, one,
                Controls.signal(level, anchor, "fire").orElseThrow().ref())).isPresent(), "waiting for loader");
        }).thenExecute(() -> CbcCannon.mounted(mount).orElseThrow().quickBreech().toggleOpening())
            .thenExecuteAfter(24, () -> {
                submitted[0] = labor.census().submitted();
                var problems = services.run(name, "shoot", Map.of());
                helper.assertTrue(problems.isEmpty(), "timed cannon run accepted: " + problems);
            }).thenWaitUntil(() -> helper.assertTrue(when[2] >= 0 && services.recent(name)
                .filter(result -> result.ending().equals("done")).isPresent(), "waiting for timed shot"))
            .thenExecute(() -> {
                helper.assertTrue(when[0] >= 0 && when[1] - when[0] == 8 && when[2] - when[0] == 48,
                    "expected shell, powder and fire at relative ticks 0, 8 and 48: "
                        + java.util.Arrays.toString(when));
                helper.assertTrue(labor.census().submitted() == submitted[0], "the loader used no plan nodes");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 1000)
    public static void residentLoadsShellAndThreeChargesAndFires(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        var target = prepareRange(helper, pos);
        // A quick-firing breech is rated for two charges; a datapack may strengthen it as a server owner can.
        strengthen(helper, block("steel_quickfiring_breech"), 4);
        var mount = mount(level, pos, "steel", "quickfiring_breech", 3, 2);
        var colony = Colonies.mint(level.getServer());
        var services = DefinedFacilities.of(colony).orElseThrow();
        var charges = new AtomicInteger();
        helper.onEachTick(() -> CbcCannon.mounted(mount).ifPresent(cannon ->
            charges.accumulateAndGet(cannon.charges(), Math::max)));
        residentFires(helper, mount, target, colony, (name, data) -> {
            var preview = services.preview(name, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "three-charge cannon validates: " + preview.problems());
            helper.assertTrue(services.apply(preview).isEmpty(), "three-charge cannon installed");
            return CompletableFuture.completedFuture(null);
        }, List.of(List.of("createbigcannons:solid_shot", 1), List.of("createbigcannons:powder_charge", 3)));
        helper.onEachTick(() -> {
            if (charges.get() > 3) {
                helper.fail("the bore never holds more than three charges: " + charges.get());
            }
        });
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 1200)
    public static void residentLoadsThroughMuzzleAndFires(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        var target = prepareRange(helper, pos);
        var mount = mount(level, pos);
        var colony = Colonies.mint(level.getServer());
        var services = DefinedFacilities.of(colony).orElseThrow();
        var atMuzzle = new AtomicBoolean();
        var breechOpened = new AtomicBoolean();
        helper.onEachTick(() -> CbcCannon.mounted(mount).ifPresent(cannon -> {
            var tube = cannon.tube();
            if (!cannon.chamber(tube.getLast()).block().state().isAir()) {
                atMuzzle.set(true);
            }
            if (cannon.quickBreech() != null && cannon.quickBreech().getOpenProgress() > 0) {
                breechOpened.set(true);
            }
        }));
        residentFires(helper, mount, target, colony, (name, data) -> {
            var preview = services.preview(name, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "muzzle-loaded cannon validates: " + preview.problems());
            helper.assertTrue(services.apply(preview).isEmpty(), "muzzle-loaded cannon installed");
            return CompletableFuture.completedFuture(null);
        }, "muzzle", List.of(List.of("createbigcannons:powder_charge", 1), List.of("createbigcannons:solid_shot", 1)),
            List.of(new ItemStack(block("powder_charge")), new ItemStack(block("solid_shot"))), Map.of(), 1, true,
            () -> helper.assertTrue(atMuzzle.get() && !breechOpened.get(),
                "the munitions went in at the muzzle and the breech stayed shut"));
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 1600)
    public static void bigCartridgeFiresAndTheSpentCaseMakesWayForTheNextLoad(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        var target = prepareRange(helper, pos);
        var mount = mount(level, pos);
        var colony = Colonies.mint(level.getServer());
        var services = DefinedFacilities.of(colony).orElseThrow();
        var spent = new AtomicBoolean();
        helper.onEachTick(() -> CbcCannon.mounted(mount).ifPresent(cannon -> {
            var first = cannon.chamber(cannon.tube().getFirst()).block();
            if (CbcBallistics.spent(first)) {
                spent.set(true);
            }
        }));
        var cartridge = BigCartridgeBlockItem.getWithPower(2);
        residentFires(helper, mount, target, colony, (name, data) -> {
            var preview = services.preview(name, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "cartridge cannon validates: " + preview.problems());
            helper.assertTrue(services.apply(preview).isEmpty(), "cartridge cannon installed");
            return CompletableFuture.completedFuture(null);
        }, "breech", List.of(List.of("createbigcannons:solid_shot", 1),
                List.of("createbigcannons:big_cartridge", 1, Map.of("createbigcannons:power", 2))),
            List.of(new ItemStack(block("solid_shot")), cartridge.copy(), new ItemStack(block("solid_shot")),
                cartridge.copy()), Map.of(), 2, false, () -> {
                helper.assertTrue(spent.get(), "the first shot left a spent cartridge for the next load to clear");
                // A fast flat shot turns CBC's spread into range, so the cartridge's power is checked in the flight
                // model the routine aims with rather than at the impact.
                var readings = services.read("test_cannon").orElseThrow().readings();
                var weapon = (Map<?, ?>) readings.get("aim.ballistics");
                var munitions = (Map<?, ?>) weapon.get("munitions");
                double shell = (Double) ((Map<?, ?>) munitions.get("createbigcannons:solid_shot")).get("power");
                double level2 = (Double) ((Map<?, ?>) munitions.get("createbigcannons:big_cartridge")).get("power");
                var model = (Map<?, ?>) ((Map<?, ?>) readings.get("shoot.engagement")).get("model");
                helper.assertTrue(Math.abs((Double) model.get("speed") - (shell + 2 * level2)) < 1e-6,
                    "the flight takes the cartridge's power: " + model + " from " + munitions);
            });
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 1200)
    public static void fireControlShootsAHostileThroughTheScript(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        var target = prepareRange(helper, pos);
        var mount = mount(level, pos);
        var colony = Colonies.mint(level.getServer());
        var services = DefinedFacilities.of(colony).orElseThrow();
        var hostile = EntityType.HUSK.create(level);
        hostile.moveTo(target.x, target.y, target.z);
        hostile.setNoAi(true);
        hostile.setPersistenceRequired();
        level.addFreshEntity(hostile);
        Cleanup.on(helper, hostile::discard);
        residentFires(helper, mount, target, colony, (name, data) -> {
            var preview = services.preview(name, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "fire control cannon validates: " + preview.problems());
            helper.assertTrue(services.apply(preview).isEmpty(), "fire control cannon installed");
            return CompletableFuture.completedFuture(null);
        }, "breech", ONE_CHARGE, List.of(new ItemStack(block("solid_shot")), new ItemStack(block("powder_charge"))),
            Map.of("readings", Map.of("nearby", Map.of("probe", "folkways_vanilla:hostile_within", "range", 48)),
                "triggers", Map.of("auto", Map.of("kind", "condition", "routine", "shoot",
                    "source", "folkways_core:fire_control", "period", 10, "cooldown", 600, "enabled", true))), 1,
            false, () -> helper.assertTrue(FireControl.of(colony).assigned(services.facilities().getFirst().id(),
                    level.getServer()).filter(each -> each.target().target().equals(hostile.getUUID())).isPresent()
                    || !hostile.isAlive() || hostile.getHealth() < hostile.getMaxHealth(),
                "fire control assigned the husk to the cannon"));
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 200)
    public static void loadPastSafeStressIsRefused(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        var anchor = WorldSpaces.at(level, pos);
        var mount = mount(level, pos, "cast_iron", "quickfiring_breech", 3, 1);
        var writer = helper.spawn(EntityType.PIG, new BlockPos(4, 1, 6));
        var colony = Colonies.mint(level.getServer());
        Cleanup.on(helper, () -> {
            mount.disassemble();
            colony.raze();
        });
        level.setBlockAndUpdate(pos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence().thenExecuteAfter(3, () -> {
            var port = Ports.at(level, anchor, ResourceKind.ITEM, "breech").orElseThrow();
            var shell = new ItemStack(block("solid_shot"));
            port.prepareDeposit(level, writer, shell);
        }).thenExecuteAfter(12, () -> {
            var port = Ports.at(level, anchor, ResourceKind.ITEM, "breech").orElseThrow();
            var shell = new ItemStack(block("solid_shot"));
            var powder = new ItemStack(block("powder_charge"));
            helper.assertTrue(port.deposit(level, writer, shell, false).getCount() == 1, "shell accepted");
            helper.assertTrue(port.deposit(level, writer, powder, false).getCount() == 1, "first charge accepted");
            helper.assertTrue(port.deposit(level, writer, powder, false).getCount() == 1,
                "second charge accepted after a ram");
            helper.assertTrue(read(helper, anchor, "charges").equals(Value.of(2))
                && read(helper, anchor, "projectile").equals(new Value.Text("createbigcannons:solid_shot"))
                && read(helper, anchor, "stress").equals(Value.of(2, 2))
                && read(helper, anchor, "loaded").equals(Value.of(true)), "readings report the two-charge load");
            helper.assertTrue(port.deposit(level, writer, powder, true).isEmpty()
                && port.deposit(level, writer, powder, false).isEmpty(), "a third charge is refused");
            var reason = ((Value.Text) read(helper, anchor, "refusal")).value();
            helper.assertTrue(reason.contains("safe limit of 2"), "the refusal names CBC's safe limit: " + reason);
            helper.assertTrue(read(helper, anchor, "charges").equals(Value.of(2)), "the refused charge left the bore");
            var services = DefinedFacilities.of(colony).orElseThrow();
            var xyz = List.of(pos.getX(), pos.getY(), pos.getZ());
            var data = Map.<String, Object>of("name", "overloaded",
                "extent", DefinitionCodec.extent(FacilityExtent.blocks(List.of(anchor))), "anchor", xyz,
                "controls", Map.of("aim", Map.of("part", "aim", "offset", List.of(0, 0, 0)),
                    "fire", Map.of("part", "fire", "offset", List.of(0, 0, 0)),
                    "breech", Map.of("part", "breech", "offset", List.of(0, 0, 0))),
                "routines", Map.of("shoot", ShootRoutine.with(Map.of("aim", "aim", "fire", "fire", "breech", "breech",
                    "ammunition", List.of(List.of("createbigcannons:solid_shot", 1),
                        List.of("createbigcannons:powder_charge", 3))))),
                "triggers", Map.of("order", Map.of("kind", "manual", "routine", "shoot")));
            var preview = services.preview("overloaded", Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty() && services.apply(preview).isEmpty(),
                "the overloaded routine is defined: " + preview.problems());
            var problems = services.run("overloaded", "shoot", Map.of("target", Value.of(pos.getX() + 14,
                pos.getY(), pos.getZ())));
            helper.assertTrue(problems.isEmpty(), "the overloaded run is submitted: " + problems);
        }).thenWaitUntil(() -> helper.assertTrue(DefinedFacilities.of(colony).orElseThrow().recent("overloaded")
                .flatMap(result -> result.reason()).filter(reason -> reason.contains("safe limit of 2")).isPresent(),
            "the script refuses the overload with CBC's reason: "
                + DefinedFacilities.of(colony).orElseThrow().recent("overloaded")))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 300)
    public static void autocannonTakesContainerAndFires(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        var anchor = WorldSpaces.at(level, pos);
        var mount = autocannon(level, pos);
        var writer = helper.spawn(EntityType.PIG, new BlockPos(4, 1, 6));
        var fired = new AtomicInteger();
        Consumer<EntityJoinLevelEvent> observe = event -> {
            if (event.getLevel() == level && event.getEntity() instanceof AbstractCannonProjectile projectile
                    && projectile.position().distanceTo(Vec3.atCenterOf(pos)) < 12) {
                fired.incrementAndGet();
                Cleanup.on(helper, projectile::discard);
            }
        };
        NeoForge.EVENT_BUS.addListener(observe);
        Cleanup.on(helper, () -> {
            NeoForge.EVENT_BUS.unregister(observe);
            mount.disassemble();
        });
        level.setBlockAndUpdate(pos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        var rounds = new AtomicInteger();
        helper.startSequence().thenExecuteAfter(3, () -> {
            var parts = Controls.within(level, List.of(anchor)).stream().map(Control::part).toList();
            helper.assertTrue(parts.containsAll(List.of("aim", "fire", "ammo")) && !parts.contains("breech"),
                "a mounted autocannon offers aim, fire and ammo: " + parts);
            var fire = Controls.signal(level, anchor, "fire").orElseThrow();
            helper.assertTrue(fire.type() instanceof SignalType.Real, "autocannon fire takes a level");
            var aim = Controls.signal(level, anchor, "aim").orElseThrow();
            helper.assertTrue(aim.write(level, writer, Value.of(0, 5)) == Signal.Write.WRITTEN, "autocannon aimed");
            var port = Ports.at(level, anchor, ResourceKind.ITEM, "ammo").orElseThrow();
            var container = filledContainer(8);
            helper.assertTrue(port.deposit(level, writer, container, false).getCount() == 1, "container inserted");
            helper.assertTrue(port.deposit(level, writer, filledContainer(8), true).isEmpty(),
                "a second container waits for the first to come out");
            helper.assertTrue(read(helper, anchor, "ammo").equals(Value.of(8))
                && read(helper, anchor, "loaded").equals(Value.of(true)), "readings count the container's rounds");
            helper.assertTrue(fire.write(level, writer, Value.of(15)) == Signal.Write.WRITTEN, "fire level set");
            helper.assertTrue(read(helper, anchor, "fire_rate").equals(Value.of(15)), "fire rate reads back");
        }).thenWaitUntil(() -> helper.assertTrue(fired.get() >= 2, "waiting for autocannon rounds: " + fired.get()))
            .thenExecute(() -> {
                var fire = Controls.signal(level, anchor, "fire").orElseThrow();
                helper.assertTrue(fire.write(level, writer, Value.of(0)) == Signal.Write.WRITTEN, "ceasefire");
                rounds.set((int) ((Value.Real) read(helper, anchor, "ammo")).value());
                helper.assertTrue(rounds.get() < 8, "rounds left the container: " + rounds.get());
                var port = Ports.at(level, anchor, ResourceKind.ITEM, "ammo").orElseThrow();
                var spent = port.draw(level, writer, new ItemStack(block("autocannon_ammo_container")), false);
                helper.assertTrue(!spent.isEmpty() && AutocannonAmmoContainerItem.getTotalAmmoCount(spent)
                    == rounds.get(), "the used container came out with what was left: " + spent);
                helper.assertTrue(port.contents(level).isEmpty(), "the breech is empty again");
                helper.assertTrue(port.deposit(level, writer, filledContainer(4), false).getCount() == 1,
                    "a fresh container goes in");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 200)
    public static void dropMortarRoundLaunches(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 1, 4));
        var anchor = WorldSpaces.at(level, pos);
        var mount = mount(level, pos, "wrought_iron", "drop_mortar_end", 1, 0);
        var writer = helper.spawn(EntityType.PIG, new BlockPos(4, 1, 6));
        var launched = new AtomicBoolean();
        Consumer<EntityJoinLevelEvent> observe = event -> {
            if (event.getLevel() == level && event.getEntity() instanceof AbstractCannonProjectile projectile
                    && projectile.position().distanceTo(Vec3.atCenterOf(pos)) < 12) {
                launched.set(true);
                Cleanup.on(helper, projectile::discard);
            }
        };
        NeoForge.EVENT_BUS.addListener(observe);
        Cleanup.on(helper, () -> {
            NeoForge.EVENT_BUS.unregister(observe);
            mount.disassemble();
        });
        level.setBlockAndUpdate(pos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence().thenExecuteAfter(3, () -> {
            var parts = Controls.within(level, List.of(anchor)).stream().map(Control::part).toList();
            helper.assertTrue(parts.containsAll(List.of("aim", "ammo")) && !parts.contains("fire")
                && !parts.contains("breech"), "a mounted drop mortar offers aim and ammo: " + parts);
            var aim = Controls.signal(level, anchor, "aim").orElseThrow();
            helper.assertTrue(aim.write(level, writer, Value.of(0, 45)) == Signal.Write.WRITTEN, "mortar aimed");
            var port = Ports.at(level, anchor, ResourceKind.ITEM, "ammo").orElseThrow();
            var round = new ItemStack(block("drop_mortar_shell"));
            helper.assertTrue(port.deposit(level, writer, round, false).getCount() == 1, "round dropped");
            helper.assertTrue(read(helper, anchor, "loaded").equals(Value.of(true))
                && port.contents(level).size() == 1, "the falling round reads as loaded");
            helper.assertTrue(port.deposit(level, writer, round, true).isEmpty(), "one round falls at a time");
            helper.assertFalse(launched.get(), "the mortar waits dropMortarDelay before launching");
        }).thenWaitUntil(() -> helper.assertTrue(launched.get(), "waiting for the mortar round"))
            .thenExecute(() -> {
                var port = Ports.at(level, anchor, ResourceKind.ITEM, "ammo").orElseThrow();
                helper.assertTrue(port.contents(level).isEmpty(), "the round left the mortar");
                helper.assertTrue(port.deposit(level, writer, new ItemStack(block("drop_mortar_shell")), true)
                    .isEmpty(), "the dropper waits dropMortarItemCooldown before the same round again");
            }).thenSucceed();
    }

    private static ItemStack filledContainer(int rounds) {
        var cartridge = new ItemStack(item("autocannon_cartridge"));
        AutocannonCartridgeItem.writeProjectile(new ItemStack(item("ap_autocannon_round")), cartridge);
        var container = new ItemStack(block("autocannon_ammo_container"));
        container.set(CBCDataComponents.AMMO, ItemContainerContents.fromItems(List.of(cartridge.copyWithCount(rounds))));
        return container;
    }

    private static CannonMountBlockEntity autocannon(ServerLevel level, BlockPos pos) {
        var direction = Direction.EAST;
        var bore = pos.above(2);
        level.setBlockAndUpdate(pos, block("cannon_mount").defaultBlockState()
            .setValue(CannonMountBlock.HORIZONTAL_FACING, direction));
        crank(level, pos.below(), Direction.DOWN);
        crank(level, pos.south(), Direction.SOUTH);
        level.setBlockAndUpdate(bore, block("cast_iron_autocannon_breech").defaultBlockState()
            .setValue(DirectionalBlock.FACING, direction));
        for (int offset = 1; offset <= 2; offset++) {
            level.setBlockAndUpdate(bore.east(offset), block("cast_iron_autocannon_barrel").defaultBlockState()
                .setValue(DirectionalBlock.FACING, direction));
        }
        for (int offset = 0; offset <= 2; offset++) {
            AutocannonBlock.onPlace(level, bore.east(offset));
        }
        return (CannonMountBlockEntity) level.getBlockEntity(pos);
    }

    @SuppressWarnings("unchecked")
    private static void strengthen(GameTestHelper helper, Block breech, int strength) {
        try {
            var field = BigCannonBreechStrengthHandler.class.getDeclaredField("BREECH_STRENGTHS");
            field.setAccessible(true);
            var strengths = (Map<Block, Integer>) field.get(null);
            Integer previous = strengths.put(breech, strength);
            Cleanup.on(helper, () -> {
                if (previous == null) {
                    strengths.remove(breech);
                } else {
                    strengths.put(breech, previous);
                }
            });
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static net.minecraft.world.item.Item item(String name) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("createbigcannons", name));
    }

    private static Vec3 prepareRange(GameTestHelper helper, BlockPos pos) {
        for (BlockPos floor : BlockPos.betweenClosed(pos.offset(-2, -1, -3), pos.offset(18, -1, 5))) {
            helper.getLevel().setBlockAndUpdate(floor, Blocks.OBSIDIAN.defaultBlockState());
        }
        return Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(17, 1, 4)));
    }

    static void residentFires(GameTestHelper helper, CannonMountBlockEntity mount, Vec3 target) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var services = DefinedFacilities.of(colony).orElseThrow();
        residentFires(helper, mount, target, colony, (name, data) -> {
            var preview = services.preview(name, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "assembled CBC facility validates: " + preview.problems());
            helper.assertTrue(services.apply(preview).isEmpty(), "CBC facility installed");
            return CompletableFuture.completedFuture(null);
        });
    }

    private static final List<List<Object>> ONE_CHARGE = List.of(List.of("createbigcannons:solid_shot", 1),
        List.of("createbigcannons:powder_charge", 1));

    private static void residentFires(GameTestHelper helper, CannonMountBlockEntity mount, Vec3 target, Colony colony,
            BiFunction<String, Map<String, Object>, CompletableFuture<Void>> define) {
        residentFires(helper, mount, target, colony, define, ONE_CHARGE);
    }

    private static void residentFires(GameTestHelper helper, CannonMountBlockEntity mount, Vec3 target, Colony colony,
            BiFunction<String, Map<String, Object>, CompletableFuture<Void>> define, List<List<Object>> ammunition) {
        List<ItemStack> stock = ammunition.stream().map(entry -> new ItemStack(
            BuiltInRegistries.ITEM.get(ResourceLocation.parse((String) entry.get(0))), (Integer) entry.get(1))).toList();
        residentFires(helper, mount, target, colony, define, "breech", ammunition, stock, Map.of(), 1, true,
            () -> { });
    }

    /**
     * A resident loads the cannon through {@code port} ("breech" or "muzzle") from colony stock and fires
     * {@code rounds} shots, each run asked for by hand unless {@code more} brings triggers of its own.
     */
    private static void residentFires(GameTestHelper helper, CannonMountBlockEntity mount, Vec3 target, Colony colony,
            BiFunction<String, Map<String, Object>, CompletableFuture<Void>> define, String port,
            List<List<Object>> ammunition, List<ItemStack> stock, Map<String, Object> more, int rounds,
            boolean checkImpact, Runnable verify) {
        var level = helper.getLevel();
        var anchor = WorldSpaces.at(level, mount.getBlockPos());
        var xyz = List.of(anchor.cell().getX(), anchor.cell().getY(), anchor.cell().getZ());
        String name = "test_cannon";
        // Only the mount belongs to the cannon facility. The supply barrel is a separate store aboard.
        var extent = FacilityExtent.blocks(List.of(anchor));
        var controls = new java.util.LinkedHashMap<String, Object>();
        var with = new java.util.LinkedHashMap<String, Object>(Map.of("aim", "aim", "fire", "fire",
            "ammunition", ammunition, port, port));
        for (String part : port.equals("muzzle") ? List.of("aim", "fire", "muzzle", "ram")
                : List.of("aim", "fire", port)) {
            controls.put(part, Map.of("part", part, "offset", List.of(0, 0, 0)));
        }
        var data = new java.util.LinkedHashMap<String, Object>(Map.of("name", name,
            "extent", DefinitionCodec.extent(extent), "anchor", xyz, "controls", controls,
            "routines", Map.of("shoot", ShootRoutine.with(with)),
            "triggers", Map.of("order", Map.of("kind", "manual", "routine", "shoot"))));
        data.putAll(more);
        var services = DefinedFacilities.of(colony).orElseThrow();
        boolean manual = !more.containsKey("triggers");
        residentFires(helper, mount, target, colony, name, () -> define.apply(name, data), () -> {
            if (manual) {
                var problems = services.run(name, "shoot", Map.of("target", Value.of(target.x, target.y, target.z)));
                helper.assertTrue(problems.isEmpty(), "firing run submitted: " + problems);
            }
            return CompletableFuture.completedFuture(null);
        }, checkImpact, stock, rounds, verify);
    }

    private static void residentFires(GameTestHelper helper, CannonMountBlockEntity mount, Vec3 target, Colony colony,
            String name, Supplier<CompletableFuture<Void>> define, Supplier<CompletableFuture<Void>> run,
            boolean checkImpact) {
        residentFires(helper, mount, target, colony, name, define, run, checkImpact,
            List.of(new ItemStack(block("solid_shot")), new ItemStack(block("powder_charge"))), 1, () -> { });
    }

    private static void residentFires(GameTestHelper helper, CannonMountBlockEntity mount, Vec3 target, Colony colony,
            String name, Supplier<CompletableFuture<Void>> define, Supplier<CompletableFuture<Void>> run,
            boolean checkImpact, List<ItemStack> stock, int rounds, Runnable verify) {
        var level = helper.getLevel();
        var pos = mount.getBlockPos();
        var anchor = WorldSpaces.at(level, pos);
        var origin = WorldSpaces.world(level, anchor).orElseThrow();
        var resident = PersonBody.RESIDENT.get().create(level);
        resident.moveTo(WorldSpaces.world(level, WorldSpaces.at(level, pos.south(2))).orElseThrow()
            .add(0, .02, 0));
        resident.setOnGround(true);
        level.addFreshEntity(resident);
        colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(colony.id());
        colony.entered(resident);
        var labor = new LaborFixture(colony, level);
        helper.onEachTick(labor::tick);
        var supply = pos.south(3).west();
        level.setBlockAndUpdate(supply, Blocks.BARREL.defaultBlockState());
        var store = Containers.at(level, supply).orElseThrow();
        for (int slot = 0; slot < stock.size(); slot++) {
            store.setItem(slot, stock.get(slot).copy());
        }
        colony.stores(CbcIntegration.id("test_supply"), level.dimension(),
            List.of(WorldSpaces.at(level, supply)));
        var shot = new AtomicReference<AbstractBigCannonProjectile>();
        var shots = new AtomicInteger();
        var launchArea = new AABB(origin, origin).inflate(8);
        var storageArea = new AABB(pos).inflate(8);
        Consumer<EntityJoinLevelEvent> collect = event -> {
            if (event.getEntity() instanceof AbstractBigCannonProjectile projectile
                    && event.getLevel() == level && (launchArea.contains(projectile.position())
                        || storageArea.contains(projectile.position()))) {
                if (shot.get() != null) {
                    shot.get().discard();
                }
                shot.set(projectile);
                shots.incrementAndGet();
            }
        };
        NeoForge.EVENT_BUS.addListener(collect);
        Cleanup.on(helper, () -> {
            NeoForge.EVENT_BUS.unregister(collect);
            if (shot.get() != null) {
                shot.get().discard();
            }
            mount.disassemble();
            labor.close();
            resident.discard();
            colony.raze();
        });

        var services = DefinedFacilities.of(colony).orElseThrow();
        var pending = new AtomicReference<CompletableFuture<Void>>();
        // Every charge widens CBC's spread; a big cartridge counts as its power in charges.
        double charges = stock.stream().mapToDouble(stack -> stack.is(block("powder_charge").asItem())
            ? stack.getCount() : stack.is(block("big_cartridge").asItem())
            ? stack.getCount() * stack.getOrDefault(CBCDataComponents.POWER, 0) : 0).sum() / rounds;
        level.setBlockAndUpdate(pos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        var sequence = helper.startSequence().thenExecuteAfter(3, () -> pending.set(define.get()))
            .thenWaitUntil(AsyncWait.until(helper, () -> pending.get().isDone(), "cannon definition"))
            .thenExecute(() -> {
                pending.get().join();
                pending.set(run.get());
            }).thenWaitUntil(AsyncWait.until(helper, () -> pending.get().isDone(), "cannon run"))
            .thenExecute(() -> pending.get().join());
        for (int round = 1; round <= rounds; round++) {
            int fired = round;
            sequence = sequence.thenWaitUntil(() -> {
                var projectile = shot.get();
                helper.assertTrue(projectile != null && shots.get() >= fired, "waiting for native projectile "
                    + fired + ": " + services.recent(name) + " " + labor.diagnosis() + " " + resident.position());
                if (checkImpact) {
                    helper.assertTrue(projectile.isRemoved() || projectile.getY() <= target.y + .1,
                        "waiting for ground impact");
                    // CBC spread is sampled on all axes and every charge adds to it; on this flat shot vertical
                    // spread turns into range. At this distance one charge may miss by two blocks, each further
                    // charge by one.
                    helper.assertTrue(projectile.position().subtract(target).horizontalDistance() < 1 + charges,
                        "impact near target " + target + ", projectile ended at " + projectile.position());
                }
                helper.assertTrue(services.recent(name).filter(result -> result.ending().equals("done")).isPresent(),
                    "ordered port writes and pulse completed: " + services.recent(name));
            });
            if (round < rounds) {
                sequence = sequence.thenExecute(() -> {
                    pending.set(run.get());
                    pending.get().join();
                });
            }
        }
        // An ejected spent cartridge may be gathered back into the store; nothing live is left there.
        sequence.thenExecute(() -> helper.assertTrue(java.util.stream.IntStream.range(0, store.getContainerSize())
                .mapToObj(store::getItem).allMatch(stack -> stack.isEmpty() || stack.is(block("big_cartridge").asItem())
                    && stack.getOrDefault(CBCDataComponents.POWER, 0) == 0),
            "the resident used the whole load from the colony stock: " + stock))
            .thenExecute(verify).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 240)
    public static void cannonAimNeedsKineticInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        var mount = crankDrivenBronzeMount(level, pos);
        level.removeBlock(pos.below(), false);
        level.removeBlock(pos.north(), false);
        level.setBlockAndUpdate(pos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        var aim = new CbcAim(WorldSpaces.at(level, pos), mount);
        Value target = Value.of(243.73, 0);
        helper.startSequence().thenExecuteAfter(5, () -> {
            helper.assertTrue(aim.valid(level), "the cannon is assembled");
            helper.assertTrue(aim.write(level, null, target) == Signal.Write.REFUSED,
                "an unpowered shaft cannot change angle");
            crank(level, pos.below(), Direction.DOWN);
            helper.assertTrue(aim.write(level, null, Value.of(243.73, 10)) == Signal.Write.REFUSED,
                "a yaw crank cannot drive the pitch shaft");
            helper.assertTrue(aim.write(level, null, target) == Signal.Write.WRITTEN, "the crank takes the aim");
        }).thenWaitUntil(() -> {
            helper.assertTrue(aim.commandFeedback().orElseThrow().read(level).filter(target::equals).isPresent(),
                "yaw=" + ((CannonMountAccess) mount).folkwaysYaw() + ", shaft speed=" + mount.getYawSpeed()
                    + ", crank speed=" + ((HandCrankBlockEntity)
                        level.getBlockEntity(pos.below())).getSpeed()
                    + ", shaft source=" + mount.getYawInterface().source
                    + ", shaft limit=" + ((CannonMountInterfaceBlockEntity)
                        mount.getYawInterface()).getSequencedAngleLimit());
        }).thenExecute(() -> {
            var angles = (CannonMountAccess) mount;
            helper.assertTrue(Math.abs(Mth.wrapDegrees(angles.folkwaysYaw() - 243.73)) < .02,
                "CBC stops at the requested angle, got " + angles.folkwaysYaw());
            helper.assertTrue(angles.folkwaysPitch() == 0, "the undriven pitch stays put");
        }).thenSucceed();
    }

    private static void crank(ServerLevel level, BlockPos pos, Direction facing) {
        var crank = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "hand_crank"));
        level.setBlockAndUpdate(pos, crank.defaultBlockState().setValue(HandCrankBlock.FACING, facing));
    }

    private static void gearedCrank(ServerLevel level, BlockPos output, Direction.Axis axis,
                                    Direction handle, BlockPos offset, int reductions) {
        var small = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "cogwheel"));
        var large = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "large_cogwheel"));
        BlockPos at = output;
        for (int i = 0; i < reductions; i++) {
            level.setBlockAndUpdate(at, large.defaultBlockState().setValue(BlockStateProperties.AXIS, axis));
            at = at.offset(offset);
            level.setBlockAndUpdate(at, small.defaultBlockState().setValue(BlockStateProperties.AXIS, axis));
            at = at.relative(handle);
        }
        crank(level, at, handle);
    }

    static CannonMountBlockEntity crankDrivenBronzeMount(ServerLevel level, BlockPos pos) {
        return mount(level, pos, "bronze", "quickfiring_breech", 1, 2);
    }

    static CannonMountBlockEntity mount(ServerLevel level, BlockPos pos) {
        return mount(level, pos, 2);
    }

    private static CannonMountBlockEntity mount(ServerLevel level, BlockPos pos, int barrels) {
        return mount(level, pos, "cast_iron", "quickfiring_breech", 1, barrels);
    }

    private static CannonMountBlockEntity mount(ServerLevel level, BlockPos pos, String material, String breech,
                                                int chambers, int barrels) {
        var direction = Direction.EAST;
        var bore = pos.above(2);
        level.setBlockAndUpdate(pos, block("cannon_mount").defaultBlockState()
            .setValue(CannonMountBlock.HORIZONTAL_FACING, direction));
        if (material.equals("bronze") || breech.equals("drop_mortar_end")) {
            crank(level, pos.below(), Direction.DOWN);
            crank(level, pos.north(), Direction.NORTH);
        } else {
            int reductions = material.equals("steel") ? 2 : 1;
            var shaft = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "shaft"));
            level.setBlockAndUpdate(pos.below(), shaft.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
            level.setBlockAndUpdate(pos.north(), shaft.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
            gearedCrank(level, pos.below(2), Direction.Axis.Y, Direction.UP, new BlockPos(-1, 0, -1), reductions);
            gearedCrank(level, pos.north(2), Direction.Axis.Z, Direction.NORTH, new BlockPos(1, 1, 0), reductions);
        }
        // CBC can burst when powder ignites in a barrel. Chambers hold the charges.
        for (int offset = 0; offset < chambers; offset++) {
            level.setBlockAndUpdate(bore.east(offset), block(material + "_cannon_chamber").defaultBlockState()
                .setValue(DirectionalBlock.FACING, direction));
        }
        for (int offset = chambers; offset < chambers + barrels; offset++) {
            level.setBlockAndUpdate(bore.east(offset), block(material + "_cannon_barrel").defaultBlockState()
                .setValue(DirectionalBlock.FACING, direction));
        }
        // A drop mortar end's block state faces away from the tube it closes.
        level.setBlockAndUpdate(bore.west(), block(material + "_" + breech).defaultBlockState()
            .setValue(DirectionalBlock.FACING, breech.equals("drop_mortar_end") ? direction.getOpposite() : direction));
        for (int offset = -1; offset < chambers + barrels; offset++) {
            BigCannonBlock.onPlace(level, bore.offset(offset, 0, 0));
        }

        return (CannonMountBlockEntity) level.getBlockEntity(pos);
    }

    static Block block(String name) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createbigcannons", name));
    }

    private static Value read(GameTestHelper helper, WorldPos anchor, String probe) {
        return Readings.of(CbcIntegration.id(probe), anchor).orElseThrow().read(helper.getLevel()).orElseThrow();
    }
}
