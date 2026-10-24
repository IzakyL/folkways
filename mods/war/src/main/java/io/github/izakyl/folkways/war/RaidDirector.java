package io.github.izakyl.folkways.war;

import com.mojang.logging.LogUtils;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;

public final class RaidDirector {
    private final Colony colony;
    private final MinecraftServer server;
    private final ServerBossEvent bar = new ServerBossEvent(Component.translatable("folkways.page.raids"),
        BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
    private boolean enabled = true;
    private long eligibleTicks;
    private long next;
    private long sequence;
    private RaidInstance raid;
    private final java.util.Map<UUID, java.util.Set<UUID>> pendingReturns = new java.util.LinkedHashMap<>();

    RaidDirector(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
        CompoundTag saved = colony.kept(FolkwaysWarMod.RAIDS);
        // Missing plugin data starts a full grace period, including colonies founded before War was installed.
        if (saved.contains("schemaVersion")) {
            int version = saved.getInt("schemaVersion");
            if (version != 1 && version != 2) { throw new IllegalArgumentException("Unsupported raid save schema"); }
            enabled = saved.getBoolean("enabled"); eligibleTicks = saved.getLong("eligibleTicks");
            next = saved.getLong("next"); sequence = saved.getLong("sequence");
            if (saved.contains("raid")) {
                try { raid = RaidInstance.load(saved.getCompound("raid")); }
                catch (RuntimeException exception) { LogUtils.getLogger().error("Cannot restore raid for {}", colony.id(), exception); }
            }
            for (var value : saved.getList("pendingReturns", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                var row = (CompoundTag) value;
                var members = new java.util.LinkedHashSet<UUID>();
                for (var member : row.getList("members", net.minecraft.nbt.Tag.TAG_INT_ARRAY)) { members.add(net.minecraft.nbt.NbtUtils.loadUUID(member)); }
                if (!members.isEmpty()) { pendingReturns.put(row.getUUID("raid"), members); }
            }
            // Version one retained only the latest raid. Recover its unresolved exits without resetting its clocks.
            if (version == 1 && raid != null && !raid.active()) { rememberReturns(); }
        } else { next = WarConfig.GRACE.get(); }
        save();
    }

    public static Optional<RaidDirector> of(Colony colony) { return colony.service(FolkwaysWarMod.RAIDS, RaidDirector.class); }
    boolean active() { return raid != null && raid.active(); }
    RaidInstance raid() { return raid; }
    boolean enabled() { return enabled; }
    long countdown() { return active() ? raid.wait : Math.max(0, next - eligibleTicks); }

    void enabled(boolean value) {
        enabled = value;
        if (!value && active()) { finish(RaidInstance.Phase.CANCELLED); }
        save();
    }

    public boolean setEnabled(ServerPlayer player, boolean value) {
        boolean authorized = io.github.izakyl.folkways.front.engine.authority.ColonyAuthority.of(player, Optional.empty())
            .filter(authority -> authority.colony().id().equals(colony.id())).isPresent();
        if (!authorized) { return false; }
        enabled(value); return true;
    }

    public CompoundTag reading(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        if (io.github.izakyl.folkways.front.engine.authority.ColonyAuthority.of(player, Optional.empty())
                .filter(authority -> authority.colony().id().equals(colony.id())).isEmpty()) { return tag; }
        var registries = player.registryAccess();
        tag.putBoolean("enabled", enabled);
        tag.put("status", io.github.izakyl.folkways.front.api.ui.Pages.text(registries, status()));
        Component direction = raid == null ? Component.empty() : Component.translatable("folkways.raid.direction",
            Component.translatable("folkways.raid.direction." + net.minecraft.core.Direction.getNearest(
                raid.origin.cell().getX() - raid.target.cell().getX(), 0, raid.origin.cell().getZ() - raid.target.cell().getZ()).getName()));
        tag.put("direction", io.github.izakyl.folkways.front.api.ui.Pages.text(registries, direction));
        int burned = raid == null ? 0 : raid.target.level(server).map(level -> AttributedFires.of(level).burned(raid.id)).orElse(0);
        Component damage = raid == null ? Component.translatable("folkways.raid.no_report") : Component.translatable("folkways.raid.damage", raid.broken, raid.fires, burned);
        if (raid != null && !raid.active()) { damage = Component.translatable("folkways.raid." + raid.phase.name().toLowerCase(java.util.Locale.ROOT)).append("\n").append(damage); }
        tag.put("damage", io.github.izakyl.folkways.front.api.ui.Pages.text(registries, damage));
        var loot = Component.empty();
        if (raid != null) {
            for (var value : raid.plunder) {
                CompoundTag row = (CompoundTag) value;
                if (!row.contains("taken")) { continue; }
                ItemStack stack = ItemStack.parseOptional(registries, row.getCompound("taken"));
                if (!loot.getSiblings().isEmpty()) { loot.append("\n"); }
                loot.append(Component.translatable(row.getBoolean("dropped") ? "folkways.raid.loot_dropped" : "folkways.raid.loot_taken", stack.getHoverName(), stack.getCount()));
            }
        }
        tag.put("loot", io.github.izakyl.folkways.front.api.ui.Pages.text(registries, loot.getSiblings().isEmpty() ? Component.translatable("folkways.raid.no_loot") : loot));
        return tag;
    }

    void tick() {
        var target = active() ? Optional.of(raid.target) : RaidObjectives.centre(colony, server);
        boolean present = target.flatMap(at -> at.level(server).map(level -> members(level, at))).filter(players -> !players.isEmpty()).isPresent();
        advance(present);
    }

    void advance(boolean present) {
        if (!WarConfig.ENABLED.get() || !enabled || server.getWorldData().getDifficulty() == Difficulty.PEACEFUL) {
            if (active()) { finish(RaidInstance.Phase.CANCELLED); }
            return;
        }
        if (active()) {
            ServerLevel level = raid.target.level(server).orElse(null);
            raid.paused = !present || level == null || !level.isPositionEntityTicking(raid.target.cell());
            for (Mob mob : loaded()) { mob.setNoAi(raid.paused); if (raid.paused) { mob.setTarget(null); mob.getNavigation().stop(); } }
            syncBar(level);
            if (raid.paused) { return; }
            if (raid.phase == RaidInstance.Phase.PLUNDER) {
                raid.plunderTicks += 20;
                if (raid.plunderTicks >= WarConfig.PLUNDER_DURATION.get() || raid.remaining() == 0) { finish(RaidInstance.Phase.DEFEAT); }
                save(); return;
            }
            if (defeated() || RaidPlunder.holding(colony, level, raid, loaded())) { plunder(); return; }
            raid.elapsed += 20;
            if (raid.elapsed >= WarConfig.TIMEOUT.get()) { finish(RaidInstance.Phase.WITHDRAWN); return; }
            if (raid.phase == RaidInstance.Phase.WARNING || raid.remaining() == 0) {
                raid.wait = Math.max(0, raid.wait - 20);
                if (raid.wait == 0) {
                    if (raid.wave == raid.definition.waves().size()) { finish(RaidInstance.Phase.VICTORY); return; }
                    if (!spawnWave(level)) { raid.wait = 200; }
                }
            }
            save();
            return;
        }
        if (!present || colony.residents().size() < WarConfig.MIN_RESIDENTS.get()) { return; }
        eligibleTicks += 20;
        if (eligibleTicks >= next) {
            var target = RaidObjectives.centre(colony, server);
            if (target.isPresent()) {
                ServerLevel level = target.get().level(server).orElse(null);
                long time = level == null ? 0 : Math.floorMod(level.getDayTime(), 24000);
                if (time >= 12000 && time < 13000) { start(RaidDefinitions.INSTANCE.first()); }
            }
        }
        save();
    }

    public Component start(RaidDefinition definition) {
        if (definition == null || active() || !enabled || !WarConfig.ENABLED.get()) { return Component.translatable("folkways.raid.unavailable"); }
        Optional<WorldPos> target = RaidObjectives.centre(colony, server);
        if (target.isEmpty()) { return Component.translatable("folkways.raid.no_spawn"); }
        ServerLevel level = target.get().level(server).orElse(null);
        if (level == null || !allowed(level) || activeRaids() >= WarConfig.MAX_RAIDS.get()) { return Component.translatable("folkways.raid.unavailable"); }
        long seed = colony.id().getMostSignificantBits() ^ colony.id().getLeastSignificantBits() ^ sequence;
        Optional<WorldPos> origin = RaidObjectives.spawn(colony, server, target.get(), seed);
        if (origin.isEmpty()) { next = eligibleTicks + 1200; save(); return Component.translatable("folkways.raid.no_spawn"); }
        int strength = Math.min(WarConfig.MAX_STRENGTH.get(), 1 + colony.residents().size() / 8 + (int) Math.min(2, eligibleTicks / (32L * 24000))
            + (level.getDifficulty() == Difficulty.HARD ? 1 : 0));
        begin(definition.scaled(strength), target.get(), origin.get(), seed);
        return Component.translatable("folkways.raid.warning");
    }

    void begin(RaidDefinition definition, WorldPos target, WorldPos origin, long seed) {
        sequence++;
        raid = new RaidInstance(UUID.randomUUID(), seed, target, origin, definition, WarConfig.WARNING.get() + definition.waves().getFirst().delayTicks());
        tell("folkways.raid.warning"); save();
    }

    void plunder() {
        raid.phase = RaidInstance.Phase.PLUNDER;
        RaidPlunder.prepare(colony, raid, server);
        tell("folkways.raid.defeat"); save();
    }

    Colony colony() { return colony; }
    void escaped(Mob mob) { raid.escaped.add(mob.getUUID()); save(); RaidMembers.retire(mob); }

    private void rememberReturns() {
        for (var member : raid.members) {
            if (!member.dead() && !raid.escaped.contains(member.id())) {
                pendingReturns.computeIfAbsent(raid.id, id -> new java.util.LinkedHashSet<>()).add(member.id());
            }
        }
    }

    boolean pendingReturn(UUID attack, UUID member) { return pendingReturns.getOrDefault(attack, java.util.Set.of()).contains(member); }

    boolean returnsCargo(Mob mob) {
        UUID attack = RaidMembers.raid(mob).orElse(null);
        if (pendingReturn(attack, mob.getUUID())) { return true; }
        return raid == null || !raid.id.equals(attack) || !raid.escaped.contains(mob.getUUID());
    }

    void returned(Mob mob) {
        UUID attack = RaidMembers.raid(mob).orElse(null);
        var pending = pendingReturns.get(attack);
        if (pending != null && pending.remove(mob.getUUID())) {
            if (pending.isEmpty()) { pendingReturns.remove(attack); }
            save();
        }
    }

    private boolean defeated() {
        return colony.residents().stream().allMatch(resident -> {
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(resident.id());
                if (entity != null) { return !entity.isAlive(); }
            }
            return false;
        });
    }

    private boolean allowed(ServerLevel level) {
        return level.getDifficulty() != Difficulty.PEACEFUL && !level.getGameRules().getBoolean(GameRules.RULE_DISABLE_RAIDS)
            && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)
            && !WarConfig.DISABLED_DIMENSIONS.get().contains(level.dimension().location().toString());
    }

    private long activeRaids() { return Colonies.all(server).stream().map(RaidDirector::of).flatMap(Optional::stream).filter(RaidDirector::active).count(); }
    private int globalMembers() { return Colonies.all(server).stream().map(RaidDirector::of).flatMap(Optional::stream).filter(RaidDirector::active).mapToInt(d -> d.raid.remaining()).sum(); }

    private boolean spawnWave(ServerLevel level) {
        if (level == null || !allowed(level)) { return false; }
        var wave = raid.definition.waves().get(raid.wave);
        if (globalMembers() + wave.count() > WarConfig.MAX_MEMBERS.get()) { return false; }
        BlockPos origin = raid.origin.block(level);
        List<Mob> prepared = new ArrayList<>();
        RandomSource random = RandomSource.create(raid.seed + raid.wave);
        for (var entry : wave.entries()) {
            for (int count = 0; count < entry.count(); count++) {
                Entity entity = entry.entity().create(level);
                if (!(entity instanceof Mob mob)) { prepared.forEach(Entity::discard); return false; }
                boolean found = false;
                for (int attempt = 0; attempt < 32; attempt++) {
                    BlockPos at = origin.offset(random.nextInt(9) - 4, 0, random.nextInt(9) - 4);
                    if (RaidObjectives.safe(level, mob, at)) { found = true; break; }
                }
                if (!found) { mob.discard(); prepared.forEach(Entity::discard); return false; }
                mob.getRandom().setSeed(random.nextLong());
                RaidMembers.mark(mob, colony.id(), raid.id, entry.role());
                RaidMembers.prepareSpawn(mob, level);
                entry.equipment().forEach((slot, item) -> mob.setItemSlot(slot, new ItemStack(item)));
                mob.setPersistenceRequired(); mob.setCanPickUpLoot(false);
                prepared.add(mob);
            }
        }
        for (Mob mob : prepared) {
            raid.members.add(new RaidInstance.Member(mob.getUUID(), RaidMembers.role(mob), false));
            // Record each slot before the join event can observe the entity.
            save();
            if (!level.addFreshEntity(mob)) { finish(RaidInstance.Phase.WITHDRAWN); return false; }
        }
        raid.phase = RaidInstance.Phase.WAVES; raid.wave++; raid.waveFires = 0;
        raid.wait = raid.wave < raid.definition.waves().size() ? raid.definition.waves().get(raid.wave).delayTicks() : 0;
        tell("folkways.raid.wave"); save();
        return true;
    }

    List<Mob> loaded() {
        if (raid == null) { return List.of(); }
        List<Mob> result = new ArrayList<>();
        for (var member : raid.members) {
            for (var level : server.getAllLevels()) {
                if (level.getEntity(member.id()) instanceof Mob mob && mob.isAlive()) { result.add(mob); break; }
            }
        }
        return result;
    }

    void died(UUID id) { if (active()) { raid.died(id); save(); } }
    public void stop() { if (active()) { finish(RaidInstance.Phase.CANCELLED); } }

    private void finish(RaidInstance.Phase phase) {
        raid.phase = phase;
        rememberReturns(); save();
        bar.removeAllPlayers();
        for (Mob mob : loaded()) { RaidMembers.retire(mob); }
        RandomSource random = RandomSource.create(raid.seed);
        int min = WarConfig.INTERVAL_MIN.get(); int max = Math.max(min, WarConfig.INTERVAL_MAX.get());
        next = eligibleTicks + min + random.nextInt(max - min + 1);
        tell("folkways.raid." + phase.name().toLowerCase(java.util.Locale.ROOT)); save();
    }

    boolean owns(Mob mob) {
        return active() && RaidMembers.raid(mob).filter(raid.id::equals).isPresent()
            && raid.members.stream().anyMatch(member -> member.id().equals(mob.getUUID()) && !member.dead() && !raid.escaped.contains(member.id()));
    }

    private List<ServerPlayer> members(ServerLevel level, WorldPos at) {
        return level.players().stream().filter(player -> !player.isSpectator())
            .filter(player -> player.distanceToSqr(at.cell().getX(), at.cell().getY(), at.cell().getZ()) < 128 * 128)
            .filter(player -> Charters.member(server, colony, player.getUUID())).toList();
    }

    private void syncBar(ServerLevel level) {
        List<ServerPlayer> shown = level == null || raid.paused ? List.of() : members(level, raid.target);
        for (ServerPlayer player : List.copyOf(bar.getPlayers())) { if (!shown.contains(player)) { bar.removePlayer(player); } }
        shown.forEach(bar::addPlayer);
        bar.setName(status());
        bar.setProgress(raid.phase == RaidInstance.Phase.WARNING ? Math.clamp((float) raid.wait / Math.max(1, WarConfig.WARNING.get()), 0, 1)
            : Math.clamp((float) raid.remaining() / Math.max(1, raid.definition.waves().get(Math.max(0, raid.wave - 1)).count()), 0, 1));
    }

    public Component status() {
        if (!WarConfig.ENABLED.get()) { return Component.translatable("folkways.raid.server_disabled"); }
        if (!enabled) { return Component.translatable("folkways.raid.opted_out"); }
        if (active() && raid.paused) { return Component.translatable("folkways.raid.paused", raid.wave, raid.definition.waves().size(), raid.remaining(), (raid.wait + 19) / 20); }
        if (active() && raid.phase == RaidInstance.Phase.PLUNDER) { return Component.translatable("folkways.raid.plundering", raid.remaining()); }
        return active() ? Component.translatable(raid.paused ? "folkways.raid.paused" : "folkways.raid.status",
            raid.wave, raid.definition.waves().size(), raid.remaining(), (raid.wait + 19) / 20)
            : Component.translatable("folkways.raid.idle", (countdown() + 19) / 20);
    }

    private void tell(String key) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (Charters.member(server, colony, player.getUUID())) { player.sendSystemMessage(Component.translatable(key)); }
        }
    }

    void save() {
        CompoundTag saved = new CompoundTag();
        saved.putInt("schemaVersion", 2); saved.putBoolean("enabled", enabled);
        saved.putLong("eligibleTicks", eligibleTicks); saved.putLong("next", next); saved.putLong("sequence", sequence);
        if (raid != null) { saved.put("raid", raid.save()); }
        var returns = new net.minecraft.nbt.ListTag();
        pendingReturns.forEach((attack, members) -> {
            var row = new CompoundTag(); row.putUUID("raid", attack);
            var ids = new net.minecraft.nbt.ListTag(); members.forEach(member -> ids.add(net.minecraft.nbt.NbtUtils.createUUID(member)));
            row.put("members", ids); returns.add(row);
        });
        saved.put("pendingReturns", returns);
        colony.keep(FolkwaysWarMod.RAIDS, saved);
    }
    void close() { bar.removeAllPlayers(); }
}
