package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.PastDay;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;

final class Herd {

    enum Job {

        SHEAR(30),
        FEED(20),
        CULL(40),
        GLEAN(10);

        private final int laborTicks;

        Job(int laborTicks) {
            this.laborTicks = laborTicks;
        }

        int laborTicks() {
            return laborTicks;
        }
    }

    private static final long MILK_COOLDOWN_TICKS = 24_000L;

    private static final String TAG_MILKED = "milked";
    private static final String TAG_COW = "cow";
    private static final String TAG_WHEN = "at";

    record Tally(Pen pen, Map<Job, List<Quarry>> work) {
    }

    record Sight(ResourceLocation keeps, int target, int living, int adults, BlockPos where) {
    }

    private record Seen(WorldPos at, Stances footings) {
    }

    private final Runnable changed;

    private final PastDay made;

    private final Map<UUID, Long> milked = new LinkedHashMap<>();
    private final Map<UUID, Seen> seen = new LinkedHashMap<>();

    private final Set<UUID> claimed = new LinkedHashSet<>();
    private final List<Sight> sights = new ArrayList<>();

    Herd(Runnable changed, PastDay made) {
        this.changed = changed;
        this.made = made;
    }

    PastDay made() {
        return made;
    }

    List<Sight> sights() {
        return List.copyOf(sights);
    }

    void begin() {
        claimed.clear();
        sights.clear();
    }

    List<Tally> reconcile(ColonyView view, FrontView front, List<Workshop> udders) {
        ServerLevel level = view.level();
        long now = level.getGameTime();
        List<Tally> tallies = new ArrayList<>();
        for (Pen pen : Pen.markedOut(view, front)) {
            List<Animal> standing = standingIn(level, pen);
            int adults = 0;
            for (Animal beast : standing) {
                if (!beast.isBaby()) {
                    adults++;
                }
            }
            sights.add(new Sight(pen.keeps().type(), pen.target(), standing.size(), adults,
                pen.anchor().cell()));
            List<Animal> workable = new ArrayList<>(standing.size());
            for (Animal beast : standing) {
                if (project(level, beast)) {
                    workable.add(beast);
                }
            }
            Map<Job, List<Quarry>> work = new EnumMap<>(Job.class);
            if (pen.shear()) {
                work.put(Job.SHEAR, shear(level, workable));
            }
            pen.keeps().lays().ifPresent(produce ->
                work.put(Job.GLEAN, glean(level, pen, produce)));
            milk(level, workable, now, udders);
            if (standing.size() > pen.target()) {
                work.put(Job.CULL, cull(level, workable, standing.size() - pen.target()));
            } else if (standing.size() < pen.target()) {
                work.put(Job.FEED, feed(level, workable, pen.target() - standing.size()));
            }
            tallies.add(new Tally(pen, Map.copyOf(work)));
        }
        return List.copyOf(tallies);
    }

    void settle(long now) {
        seen.keySet().retainAll(claimed);
        boolean expired = false;
        for (Iterator<Map.Entry<UUID, Long>> records = milked.entrySet().iterator();
             records.hasNext(); ) {
            if (now - records.next().getValue() >= MILK_COOLDOWN_TICKS) {
                records.remove();
                expired = true;
            }
        }
        if (expired) {
            changed.run();
        }
    }

    void milked(UUID cow, long when) {
        milked.put(cow, when);
        changed.run();
    }

    CompoundTag save() {
        return Writer.of().children(TAG_MILKED, milked.entrySet(), Herd::saveRecord).tag();
    }

    private static CompoundTag saveRecord(Map.Entry<UUID, Long> record) {
        return Writer.of()
            .uuid(TAG_COW, record.getKey())
            .longValue(TAG_WHEN, record.getValue())
            .tag();
    }

    void load(Reader reader) {
        for (Reader record : reader.children(TAG_MILKED)) {
            Optional<UUID> cow = record.uuid(TAG_COW);
            Optional<Long> when = record.longValue(TAG_WHEN);
            if (cow.isPresent() && when.isPresent()) {
                milked.put(cow.get(), when.get());
            }
        }
    }

    private List<Animal> standingIn(ServerLevel level, Pen pen) {
        List<Animal> found = level.getEntitiesOfClass(Animal.class, pen.reach(level),
            beast -> beast.isAlive() && !beast.isRemoved()
                && BuiltInRegistries.ENTITY_TYPE.getKey(beast.getType()).equals(pen.keeps().type())
                && pen.holds(beast));
        found.sort(Comparator.comparing(Animal::getUUID));
        found.removeIf(beast -> !claimed.add(beast.getUUID()));
        return found;
    }

    private boolean project(ServerLevel level, Entity beast) {
        UUID who = beast.getUUID();
        WorldPos at = WorldSpaces.at(beast);
        Seen held = seen.get(who);
        if (held != null && held.at().equals(at)) {
            return true;
        }
        Optional<Stances> cells = Stances.of(level, Reach.workableCells(level, at.block(level)));
        cells.ifPresentOrElse(where -> seen.put(who, new Seen(at, where)), () -> seen.remove(who));
        return cells.isPresent();
    }

    private Quarry quarry(ServerLevel level, Entity subject) {
        Seen where = seen.get(subject.getUUID());
        return new Quarry(subject.getUUID(),
            new WorkSite.AtEntity(subject.getUUID(), where.at()),
            where.footings());
    }

    private List<Quarry> shear(ServerLevel level, List<Animal> workable) {
        List<Quarry> found = new ArrayList<>();
        for (Animal beast : workable) {
            if (beast instanceof Shearable woolly && woolly.readyForShearing()) {
                found.add(quarry(level, beast));
            }
        }
        return List.copyOf(found);
    }

    private List<Quarry> glean(ServerLevel level, Pen pen, Item produce) {
        List<ItemEntity> lying = level.getEntitiesOfClass(ItemEntity.class, pen.reach(level),
            drop -> drop.isAlive() && !drop.isRemoved() && drop.getItem().is(produce)
                && pen.holds(drop));
        lying.sort(Comparator.comparing(ItemEntity::getUUID));
        List<Quarry> found = new ArrayList<>();
        for (ItemEntity drop : lying) {
            if (claimed.add(drop.getUUID()) && project(level, drop)) {
                found.add(quarry(level, drop));
            }
        }
        return List.copyOf(found);
    }

    private void milk(ServerLevel level, List<Animal> workable, long now, List<Workshop> udders) {
        for (Animal beast : workable) {
            if (!(beast instanceof Cow) || beast.isBaby() || !rested(beast.getUUID(), now)) {
                continue;
            }
            Quarry cow = quarry(level, beast);
            udders.add(new MilkHost(cow, this));
        }
    }

    private boolean rested(UUID cow, long now) {
        Long last = milked.get(cow);
        return last == null || now - last >= MILK_COOLDOWN_TICKS;
    }

    private List<Quarry> cull(ServerLevel level, List<Animal> workable, int surplus) {
        List<Quarry> found = new ArrayList<>();
        for (Animal beast : workable) {
            if (found.size() >= surplus) {
                break;
            }
            if (!beast.isBaby()) {
                found.add(quarry(level, beast));
            }
        }
        return List.copyOf(found);
    }

    private List<Quarry> feed(ServerLevel level, List<Animal> workable, int deficit) {
        List<Animal> willing = new ArrayList<>();
        for (Animal beast : workable) {
            if (!beast.isBaby() && beast.canFallInLove() && !beast.isInLove()) {
                willing.add(beast);
            }
        }
        int pairs = Math.min(deficit, willing.size() / 2);
        List<Quarry> found = new ArrayList<>(pairs * 2);
        for (int fed = 0; fed < pairs * 2; fed++) {
            found.add(quarry(level, willing.get(fed)));
        }
        return List.copyOf(found);
    }
}
