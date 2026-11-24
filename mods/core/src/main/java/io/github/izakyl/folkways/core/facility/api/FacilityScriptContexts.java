package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ServerLevel;
import net.starlark.java.eval.StarlarkThread;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread context that some facility script evaluations need, taken before the evaluation leaves the server
 * thread and installed on its Starlark thread. Packages that offer script builtins register an extension here, so this
 * package names none of them.
 */
public final class FacilityScriptContexts {

    /** One kind of context. */
    public interface Extension {

        /** Whether evaluations of this script source need the context. */
        boolean wanted(String source);

        /** The context for one evaluation of a facility's script, on the server thread; null for none. */
        @Nullable
        Object take(ServerLevel level, Colony colony, FacilityDefinition facility);

        /** What a trial gets in place of the world's context; null for none. */
        @Nullable
        Object trial();

        void install(StarlarkThread thread, Object context);
    }

    /** The contexts taken for one evaluation. */
    public static final class Taken {

        static final Taken NONE = new Taken(List.of(), List.of());

        private final List<Extension> extensions;
        private final List<Object> contexts;

        private Taken(List<Extension> extensions, List<Object> contexts) {
            this.extensions = extensions;
            this.contexts = contexts;
        }

        void install(StarlarkThread thread) {
            for (int i = 0; i < extensions.size(); i++) {
                extensions.get(i).install(thread, contexts.get(i));
            }
        }
    }

    private static final List<Extension> EXTENSIONS = new CopyOnWriteArrayList<>();

    private FacilityScriptContexts() {
    }

    public static void register(Extension extension) {
        EXTENSIONS.add(Objects.requireNonNull(extension, "extension"));
    }

    static Taken take(ServerLevel level, Colony colony, FacilityDefinition facility,
                      @Nullable FacilityScripts.Compiled program) {
        if (program == null) {
            return Taken.NONE;
        }
        List<Extension> wanted = new ArrayList<>();
        List<Object> contexts = new ArrayList<>();
        for (Extension extension : EXTENSIONS) {
            if (extension.wanted(program.source().text())) {
                Object context = extension.take(level, colony, facility);
                if (context != null) {
                    wanted.add(extension);
                    contexts.add(context);
                }
            }
        }
        return wanted.isEmpty() ? Taken.NONE : new Taken(List.copyOf(wanted), List.copyOf(contexts));
    }

    static Taken trial(FacilityScripts.Compiled program) {
        List<Extension> wanted = new ArrayList<>();
        List<Object> contexts = new ArrayList<>();
        for (Extension extension : EXTENSIONS) {
            Object context = extension.wanted(program.source().text()) ? extension.trial() : null;
            if (context != null) {
                wanted.add(extension);
                contexts.add(context);
            }
        }
        return wanted.isEmpty() ? Taken.NONE : new Taken(List.copyOf(wanted), List.copyOf(contexts));
    }
}
