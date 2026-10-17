package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

record Order(WorldPos into, ItemSpec what, int low, int high, boolean standing) {

    static final int MIN_COUNT = 1;

    static final int MAX_COUNT = 9999;

    private static final String TAG_INTO = "into";
    private static final String TAG_WHAT = "what";
    private static final String TAG_LOW = "low";
    private static final String TAG_HIGH = "count";
    private static final String TAG_STANDING = "standing";

    Order {
        Objects.requireNonNull(into, "into");
        Objects.requireNonNull(what, "what");
        high = Mth.clamp(high, MIN_COUNT, MAX_COUNT);
        low = Mth.clamp(low, MIN_COUNT, high);
    }

    long replenishment(long held) {
        return standing && held >= low ? 0 : Math.max(0, high - held);
    }

    record Key(WorldPos into, ItemSpec what) {
    }

    Key key() {
        return new Key(into, what);
    }

    CompoundTag save() {
        return Writer.of()
            .blob(TAG_INTO, into.save())
            .blob(TAG_WHAT, what.save())
            .integer(TAG_LOW, low)
            .integer(TAG_HIGH, high)
            .flag(TAG_STANDING, standing)
            .tag();
    }

    static Optional<Order> load(Reader reader) {
        Optional<WorldPos> into = reader.child(TAG_INTO).flatMap(WorldPos::load);
        Optional<ItemSpec> what = reader.child(TAG_WHAT).flatMap(ItemSpec::load);
        if (into.isEmpty() || what.isEmpty()) {
            return Optional.empty();
        }
        int high = reader.integer(TAG_HIGH).orElse(MIN_COUNT);
        return Optional.of(new Order(into.get(), what.get(),
            reader.integer(TAG_LOW).orElse(high),
            high,
            reader.flag(TAG_STANDING).orElse(false)));
    }

}
