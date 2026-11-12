package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.task.TaskSnapshot;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;

/** RS2 owns the codec; callbacks execute inside its module so its package-private helper stays private. */
public final class TaskSnapshots {

    private static Function<TaskSnapshot, CompoundTag> writer;
    private static Function<CompoundTag, TaskSnapshot> reader;

    private TaskSnapshots() {
    }

    public static boolean install(Function<TaskSnapshot, CompoundTag> encode,
            Function<CompoundTag, TaskSnapshot> decode) {
        writer = encode;
        reader = decode;
        return true;
    }

    private static void load() {
        if (writer == null) {
            try {
                Class.forName("com.refinedmods.refinedstorage.common.autocrafting.autocrafter.TaskSnapshotPersistence");
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException("RS2 task snapshot codec is unavailable", failure);
            }
            if (writer == null) {
                throw new IllegalStateException("RS2 task snapshot mixin did not install its codec");
            }
        }
    }

    public static CompoundTag encode(TaskSnapshot snapshot) {
        load();
        return writer.apply(snapshot);
    }

    public static TaskSnapshot decode(CompoundTag data) {
        load();
        return reader.apply(data);
    }
}
