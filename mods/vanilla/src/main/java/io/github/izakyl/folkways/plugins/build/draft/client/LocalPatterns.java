package io.github.izakyl.folkways.plugins.build.draft.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import io.github.izakyl.folkways.base.task.Jobs;
import io.github.izakyl.folkways.library.Shelves;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.Templates;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local files are read without a world, decoded on bounded CPU workers, then adopted by the client thread. */
@OnlyIn(Dist.CLIENT)
public final class LocalPatterns {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");
    private static final int MOST_FILES = 256;
    private static final int MOST_PATHS = 8_192;
    private static final int MOST_RAW_BYTES = 8 << 20;
    private static final long MOST_NBT_BYTES = 32L << 20;
    private static final long RESERVED_BYTES = 64L << 20;
    private static final Set<Watching> watching = ConcurrentHashMap.newKeySet();
    // A reopened page may still hold its older Pattern objects; classify those by what actually parsed them.
    private static final Map<Pattern, String> own = new WeakHashMap<>();
    private static Jobs running;

    private record Watching(Jobs.Scope scope, UIElement page) {
    }

    private record Shelf(Path folder, String suffix, String prefix) {
    }

    private record File(ResourceLocation id, boolean pattern, byte[] bytes) {
    }

    private record Decoded(Map<ResourceLocation, String> sources, Map<ResourceLocation, CompoundTag> structures) {
    }

    private LocalPatterns() {
    }

    static void install() {
        NeoForge.EVENT_BUS.addListener(LocalPatterns::onTick);
        NeoForge.EVENT_BUS.addListener(LocalPatterns::onLoggingOut);
    }

    private static void onTick(ClientTickEvent.Post event) {
        // Hidden windows are retained by the UI, so REMOVED alone does not end their requests.
        watching.stream().filter(one -> !visible(one.page())).forEach(one -> one.scope().close());
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (running != null) {
            running.close();
            running = null;
        }
        watching.clear();
        own.clear();
    }

    private static Jobs jobs() {
        if (running == null) {
            running = new Jobs("folkways-library", new Jobs.Limits(4, 2 * RESERVED_BYTES, 2, 4, 2));
        }
        return running;
    }

    public static Jobs.Scope scope() {
        return jobs().scope();
    }

    public static boolean visible(UIElement page) {
        if (page.getModularUI() == null || page.getModularUI() != ModularUI.of(Minecraft.getInstance().screen)) {
            return false;
        }
        for (UIElement at = page; at != null; at = at.getParent()) {
            if (!at.isDisplayed()) {
                return false;
            }
        }
        return true;
    }

    public static Path folder() {
        // Returning a path to the folder opener must not touch disk on the render thread.
        return Patterns.SHELF.path();
    }

    public static boolean local(Pattern pattern) {
        String mine = own.get(pattern);
        return mine != null && !Objects.equals(mine, Patterns.sources().get(pattern.id()));
    }

    public static CompletableFuture<?> patterns(Jobs.Scope scope, UIElement page, Consumer<List<Pattern>> publish) {
        Map<ResourceLocation, String> offered = Patterns.sources();
        Map<ResourceLocation, CompoundTag> pieces = Templates.files();
        List<Pattern> shared = Patterns.all();
        List<Shelf> shelves = List.of(new Shelf(Templates.SHELF.path(), ".nbt", ""),
            new Shelf(Patterns.SHELF.path(), ".star", ""));
        return load(scope, page, shelves, decoded -> {
            unchanged(offered, pieces);
            // Pattern parsing and piece adoption inspect registries; they stay on the owning client thread.
            Templates.adoptOwn(decoded.structures()).forEach((id, why) -> LOGGER.error("{}: {}", id, why));
            Map<ResourceLocation, Pattern> byId = new LinkedHashMap<>();
            shared.forEach(pattern -> byId.put(pattern.id(), pattern));
            decoded.sources().forEach((id, source) -> Pattern.parse(id, source,
                wanted -> Optional.ofNullable(decoded.sources().getOrDefault(wanted, offered.get(wanted))))
                .ifPresent(pattern -> {
                    own.put(pattern, source);
                    byId.put(id, pattern);
                }));
            publish.accept(List.copyOf(byId.values()));
        });
    }

    public static CompletableFuture<?> blueprints(Jobs.Scope scope, UIElement page,
            Consumer<Map<ResourceLocation, CompoundTag>> publish) {
        Map<ResourceLocation, String> offered = Patterns.sources();
        Map<ResourceLocation, CompoundTag> pieces = Templates.files();
        return load(scope, page, List.of(new Shelf(Templates.BLUEPRINTS.path(), ".nbt", Templates.BLUEPRINT)),
            decoded -> {
                unchanged(offered, pieces);
                publish.accept(decoded.structures());
            });
    }

    private static void unchanged(Map<ResourceLocation, String> offered, Map<ResourceLocation, CompoundTag> pieces) {
        if (offered != Patterns.sources() || pieces != Templates.files()) {
            throw new CancellationException("the server's library changed while local files were read");
        }
    }

    private static CompletableFuture<?> load(Jobs.Scope scope, UIElement page, List<Shelf> shelves,
            Consumer<Decoded> publish) {
        Watching one = new Watching(scope, page);
        watching.add(one);
        CompletableFuture<?> future = jobs().submit(scope, RESERVED_BYTES, context -> read(context, shelves),
            LocalPatterns::decode, task -> Minecraft.getInstance().execute(() -> {
                if (visible(page)) {
                    task.run();
                } else {
                    scope.close();
                }
            }), publish);
        future.whenComplete((result, broken) -> watching.remove(one));
        return future;
    }

    private static List<File> read(Jobs.Context context, List<Shelf> shelves) throws Exception {
        List<File> files = new ArrayList<>();
        int bytes = 0;
        for (Shelf shelf : shelves) {
            context.check();
            if (!Files.isDirectory(shelf.folder())) {
                continue;
            }
            List<Path> paths = context.resource(() -> Files.walk(shelf.folder(), 16), stream -> {
                List<Path> found = new ArrayList<>();
                var iterator = stream.iterator();
                for (int visited = 0; iterator.hasNext(); visited++) {
                    context.check();
                    if (visited >= MOST_PATHS) {
                        throw new IOException("too many paths in the local library");
                    }
                    Path file = iterator.next();
                    if (Files.isDirectory(file) && shelf.folder().relativize(file).getNameCount() >= 16) {
                        throw new IOException("the local library exceeds the directory depth limit");
                    }
                    if (file.getFileName().toString().endsWith(shelf.suffix()) && Files.isRegularFile(file)) {
                        if (found.size() + files.size() >= MOST_FILES) {
                            throw new IOException("too many files in the local library");
                        }
                        found.add(file);
                    }
                }
                found.sort(Path::compareTo);
                return List.copyOf(found);
            });
            // Close the directory stream before asking for file-resource permits.
            for (Path path : paths) {
                context.check();
                String relative = shelf.folder().relativize(path).toString().replace('\\', '/');
                String stem = relative.substring(0, relative.length() - shelf.suffix().length());
                ResourceLocation id = ResourceLocation.tryBuild(Shelves.NAMESPACE, shelf.prefix() + stem);
                if (id == null) {
                    LOGGER.warn("{}: invalid library file name", path);
                    continue;
                }
                byte[] content = context.read(path, MOST_RAW_BYTES - bytes);
                bytes += content.length;
                files.add(new File(id, shelf.suffix().equals(".star"), content));
            }
        }
        return List.copyOf(files);
    }

    private static Decoded decode(Jobs.Context context, List<File> files) {
        Map<ResourceLocation, String> sources = new LinkedHashMap<>();
        Map<ResourceLocation, CompoundTag> structures = new LinkedHashMap<>();
        NbtAccounter quota = NbtAccounter.create(MOST_NBT_BYTES);
        for (File file : files) {
            context.check();
            if (file.pattern()) {
                sources.put(file.id(), new String(file.bytes(), StandardCharsets.UTF_8));
            } else {
                // These streams contain already-read bytes: decompression is CPU work, not virtual-thread I/O.
                try {
                    CompoundTag tag;
                    try {
                        tag = NbtIo.readCompressed(new ByteArrayInputStream(file.bytes()), quota);
                    } catch (IOException notPacked) {
                        tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(file.bytes())), quota);
                    }
                    structures.put(file.id(), tag);
                } catch (IOException | RuntimeException broken) {
                    LOGGER.warn("{}: {}", file.id(), broken.getMessage());
                }
            }
        }
        return new Decoded(Map.copyOf(sources), Map.copyOf(structures));
    }
}
