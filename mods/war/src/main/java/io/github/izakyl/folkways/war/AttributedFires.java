package io.github.izakyl.folkways.war;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/** The provenance and finite budgets outlive raiders and their colony. */
public final class AttributedFires extends SavedData {
    private static final Factory<AttributedFires> FACTORY = new Factory<>(AttributedFires::new, AttributedFires::load);
    private static final ThreadLocal<Context> TICK = new ThreadLocal<>();
    private final Map<BlockPos, UUID> fires = new HashMap<>();
    private final Map<UUID, Source> sources = new HashMap<>();

    private static final class Source {
        final UUID raid;
        final UUID colony;
        final UUID actor;
        final int spreadLimit;
        final int burnLimit;
        int spread;
        int burned;
        Source(UUID raid, UUID colony, UUID actor, int spreadLimit, int burnLimit) {
            this.raid = raid; this.colony = colony; this.actor = actor; this.spreadLimit = spreadLimit; this.burnLimit = burnLimit;
        }
    }
    private record Context(AttributedFires ledger, Source source, Mob actor, BlockPos origin) { }

    public static AttributedFires of(ServerLevel level) { return level.getDataStorage().computeIfAbsent(FACTORY, "folkways_raid_fires"); }
    public boolean attributed(BlockPos at) { return fires.containsKey(at); }
    int burned(UUID raid) { Source source = sources.get(raid); return source == null ? 0 : source.burned; }

    static boolean ignite(Mob mob, BlockPos at) {
        var director = RaidMembers.director(mob).filter(d -> d.owns(mob) && !d.raid().paused);
        if (director.isEmpty() || !(mob.level() instanceof ServerLevel level) || mob.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(at)) > 9) { return false; }
        var raid = director.get().raid(); var limits = raid.definition.limits();
        int count = mob.getPersistentData().getInt("folkways:ignitions");
        if (count >= Math.min(WarConfig.FIRE_PER_RAIDER.get(), limits.firesPerRaider())
                || raid.waveFires >= Math.min(WarConfig.FIRE_SOURCES.get(), limits.fireSources()) || !WarEdits.placeFire(level, at, mob)) { return false; }
        AttributedFires ledger = of(level);
        Source source = ledger.sources.computeIfAbsent(raid.id, id -> new Source(id, mob.getPersistentData().getCompound("folkways:raider").getUUID("colony"),
            mob.getUUID(), limits.spreadBlocks(), limits.burnedBlocks()));
        ledger.fires.put(at.immutable(), source.raid); ledger.setDirty();
        mob.getPersistentData().putInt("folkways:ignitions", count + 1);
        raid.fires++; raid.waveFires++; director.get().save();
        mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        level.playSound(null, at, net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.HOSTILE, 1, .9F + mob.getRandom().nextFloat() * .2F);
        level.gameEvent(mob, net.minecraft.world.level.gameevent.GameEvent.BLOCK_PLACE, at);
        return true;
    }

    // Called only around the original FireBlock tick, so unrelated player fire keeps vanilla behaviour.
    public static boolean begin(ServerLevel level, BlockPos at) {
        AttributedFires ledger = of(level);
        UUID id = ledger.fires.get(at);
        if (id == null) { return true; }
        Source source = ledger.sources.get(id);
        if (source == null) { return false; }
        if (!WarEdits.fireRules(level)) { level.scheduleTick(at, Blocks.FIRE, 40); return false; }
        Mob actor = level.getEntity(source.actor) instanceof Mob mob ? mob : EntityType.ZOMBIE.create(level);
        if (actor == null) { return false; }
        if (!actor.isAddedToLevel()) { actor.setUUID(source.actor); RaidMembers.mark(actor, source.colony, source.raid, "arsonist"); actor.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5); }
        TICK.set(new Context(ledger, source, actor, at.immutable()));
        return true;
    }

    public static void end(ServerLevel level, BlockPos at) {
        Context context = TICK.get();
        if (context == null) { return; }
        if (!level.getBlockState(at).is(Blocks.FIRE)) { context.ledger.fires.remove(at); context.ledger.setDirty(); }
        TICK.remove();
    }

    public static boolean burn(net.minecraft.world.level.Level level, BlockPos at) {
        Context context = TICK.get();
        if (context == null) { return true; }
        Source source = context.source;
        if (!(level instanceof ServerLevel server)) { return false; }
        int cost = WarEdits.burnCost(server, at, context.actor, Math.min(WarConfig.BURNED.get(), source.burnLimit) - source.burned);
        if (cost == 0) { return false; }
        source.burned += cost; context.ledger.setDirty();
        return true;
    }

    public static boolean spread(ServerLevel level, BlockPos at, BlockState state, int flags) {
        Context context = TICK.get();
        if (context == null || at.equals(context.origin)) { return level.setBlock(at, state, flags); }
        Source source = context.source;
        if (source.spread >= Math.min(WarConfig.FIRE_SPREAD.get(), source.spreadLimit) || !WarEdits.placeFire(level, at, context.actor, state)) { return false; }
        source.spread++; context.ledger.fires.put(at.immutable(), source.raid); context.ledger.setDirty();
        return true;
    }

    public static boolean burningFire(net.minecraft.world.level.Level level, BlockPos at, BlockState state, int flags) {
        Context context = TICK.get();
        boolean result = context == null || !(level instanceof ServerLevel server) ? level.setBlock(at, state, flags)
            : WarEdits.burningFire(server, at, context.actor, state);
        if (result && context != null && state.is(Blocks.FIRE)) {
            context.ledger.fires.put(at.immutable(), context.source.raid); context.ledger.setDirty();
        }
        return result;
    }

    public void forget(BlockPos at) { if (fires.remove(at) != null) { setDirty(); } }

    void prune(ServerLevel level) {
        if (fires.entrySet().removeIf(entry -> level.isLoaded(entry.getKey()) && !level.getBlockState(entry.getKey()).is(Blocks.FIRE))) { setDirty(); }
        java.util.Set<UUID> burning = new java.util.HashSet<>(fires.values());
        if (sources.entrySet().removeIf(entry -> !burning.contains(entry.getKey())
                && io.github.izakyl.folkways.core.api.colony.Colonies.of(level.getServer(), entry.getValue().colony)
                    .flatMap(RaidDirector::of).map(d -> d.raid() == null || !d.raid().id.equals(entry.getKey())).orElse(true))) { setDirty(); }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", 1);
        ListTag sourceTags = new ListTag();
        for (Source source : sources.values()) {
            CompoundTag row = new CompoundTag(); row.putUUID("raid", source.raid); row.putUUID("colony", source.colony); row.putUUID("actor", source.actor);
            row.putInt("spreadLimit", source.spreadLimit); row.putInt("burnLimit", source.burnLimit); row.putInt("spread", source.spread); row.putInt("burned", source.burned); sourceTags.add(row);
        }
        ListTag fireTags = new ListTag();
        fires.forEach((at, raid) -> { CompoundTag row = new CompoundTag(); row.putLong("pos", at.asLong()); row.putUUID("raid", raid); fireTags.add(row); });
        tag.put("sources", sourceTags); tag.put("fires", fireTags); return tag;
    }

    static AttributedFires load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("schemaVersion") != 1) { throw new IllegalArgumentException("Unsupported raid fire save schema"); }
        AttributedFires ledger = new AttributedFires();
        for (Tag value : tag.getList("sources", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            Source source = new Source(row.getUUID("raid"), row.getUUID("colony"), row.getUUID("actor"), row.getInt("spreadLimit"), row.getInt("burnLimit"));
            source.spread = row.getInt("spread"); source.burned = row.getInt("burned"); ledger.sources.put(source.raid, source);
        }
        for (Tag value : tag.getList("fires", Tag.TAG_COMPOUND)) { CompoundTag row = (CompoundTag) value; ledger.fires.put(BlockPos.of(row.getLong("pos")), row.getUUID("raid")); }
        return ledger;
    }
}
