package io.github.izakyl.folkways.front.ui.screen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import net.neoforged.fml.loading.FMLPaths;

final class WindowGeometry {

    private static final String FILE = "folkways-windows.properties";
    private static final String COMMENT = "folkways: remembered window positions and sizes";

    record Box(float x, float y, float width, float height) {
    }

    private static final Map<String, Box> REMEMBERED = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean dirty;

    private WindowGeometry() {
    }

    static synchronized Box of(String id, Box byDefault) {
        load();
        return REMEMBERED.getOrDefault(id, byDefault);
    }

    static synchronized void put(String id, Box box) {
        load();
        if (!box.equals(REMEMBERED.put(id, box))) {
            dirty = true;
        }
    }

    static synchronized void forget(String id) {
        load();
        if (REMEMBERED.remove(id) != null) {
            dirty = true;
        }
    }

    static synchronized void reset() {
        loaded = true;
        if (!REMEMBERED.isEmpty()) {
            REMEMBERED.clear();
            dirty = true;
        }
        flush();
    }

    static synchronized void flush() {
        if (!dirty) {
            return;
        }
        dirty = false;
        Properties saved = new Properties();
        REMEMBERED.forEach((id, box) -> saved.setProperty(id,
            box.x() + "," + box.y() + "," + box.width() + "," + box.height()));
        try (OutputStream out = Files.newOutputStream(path())) {
            saved.store(out, COMMENT);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = path();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties saved = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            saved.load(in);
        } catch (IOException | RuntimeException ignored) {
            return;
        }
        saved.stringPropertyNames().forEach(id -> parse(saved.getProperty(id))
            .ifPresent(box -> REMEMBERED.put(id, box)));
    }

    private static java.util.Optional<Box> parse(String written) {
        String[] parts = written == null ? new String[0] : written.split(",");
        if (parts.length != 4) {
            return java.util.Optional.empty();
        }
        try {
            Box box = new Box(
                Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()),
                Float.parseFloat(parts[2].trim()), Float.parseFloat(parts[3].trim()));
            return Float.isFinite(box.x()) && Float.isFinite(box.y())
                && Float.isFinite(box.width()) && Float.isFinite(box.height())
                && box.width() > 0 && box.height() > 0
                ? java.util.Optional.of(box) : java.util.Optional.empty();
        } catch (NumberFormatException malformed) {
            return java.util.Optional.empty();
        }
    }

    private static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }
}
