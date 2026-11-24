package io.github.izakyl.folkways.assistant.client;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

final class SessionHistory {

    static final int MAX_SESSIONS = 20;
    static final int MAX_BYTES = 512 * 1024;
    static final long MAX_AGE = Duration.ofDays(90).toMillis();
    private static final int VERSION = 1;

    record Summary(UUID id, long updated, String title) {
    }

    private final Path directory;

    SessionHistory(Path root, String realm, UUID player) {
        String hash = Hashing.sha256().hashString(realm, StandardCharsets.UTF_8).toString();
        directory = root.resolve(hash).resolve(player.toString());
    }

    static SessionHistory connected(Minecraft client) {
        if (client.player == null) {
            return null;
        }
        var integrated = client.getSingleplayerServer();
        var remote = client.getCurrentServer();
        if (integrated == null && remote == null) {
            return null;
        }
        String realm = integrated != null
                ? "world:" + integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
                : "server:" + remote.ip.trim().toLowerCase(Locale.ROOT);
        return new SessionHistory(client.gameDirectory.toPath().resolve("folkways/assistant/sessions"), realm,
                client.player.getUUID());
    }

    synchronized void save(UUID id, JsonObject snapshot) throws IOException {
        snapshot.addProperty("version", VERSION);
        snapshot.addProperty("updated", System.currentTimeMillis());
        JsonArray entries = snapshot.getAsJsonArray("entries");
        byte[] data = bytes(snapshot);
        if (data.length > MAX_BYTES) {
            JsonArray kept = new JsonArray();
            int first = entries.size();
            int remaining = MAX_BYTES - data.length + bytes(entries).length - 2;
            for (int index = entries.size() - 1; index >= 0; index--) {
                var entry = entries.get(index);
                remaining -= bytes(entry).length + 1;
                if (remaining < 0) {
                    break;
                }
                first = index;
            }
            for (int index = first; index < entries.size(); index++) {
                kept.add(entries.get(index));
            }
            snapshot.add("entries", kept);
            data = bytes(snapshot);
            if (data.length > MAX_BYTES) {
                throw new IOException("Assistant session exceeds size limit");
            }
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "session-", ".tmp");
        try {
            Files.write(temporary, data);
            Files.move(temporary, file(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        prune();
    }

    private static byte[] bytes(JsonElement value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    JsonElement scriptRead(UUID current, String path, HolderLookup.Provider registries) throws IOException {
        if (path.equals("client/history")) {
            JsonArray ids = new JsonArray();
            for (Summary session : list()) {
                if (!session.id().equals(current)) {
                    ids.add(session.id().toString());
                }
            }
            return ids;
        }
        String prefix = "client/history/";
        if (!path.startsWith(prefix)) {
            throw new IllegalArgumentException("Unknown client history path");
        }
        String key = path.substring(prefix.length());
        UUID id = UUID.fromString(key);
        JsonObject snapshot = key.equals(id.toString()) && !id.equals(current) ? read(id) : null;
        if (snapshot == null) {
            throw new IOException("Saved session unavailable");
        }
        JsonObject data = new JsonObject();
        for (String field : List.of("title", "updated", "turns")) {
            data.add(field, snapshot.get(field));
        }
        JsonArray entries = new JsonArray();
        for (JsonElement value : snapshot.getAsJsonArray("entries")) {
            JsonObject row = value.getAsJsonObject();
            Component component = Component.Serializer.fromJson(row.get("component").getAsString(), registries);
            if (component != null) {
                JsonObject entry = new JsonObject();
                entry.add("role", row.get("role"));
                entry.addProperty("text", component.getString());
                entries.add(entry);
            }
        }
        data.add("entries", entries);
        return data;
    }

    synchronized JsonObject read(UUID id) throws IOException {
        Path path = file(id);
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) {
            return null;
        }
        try {
            JsonObject snapshot = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (snapshot.get("version").getAsInt() == VERSION && snapshot.get("title").isJsonPrimitive()
                    && snapshot.get("turns").isJsonPrimitive() && snapshot.get("scratch").isJsonObject()
                    && snapshot.get("entries").isJsonArray()
                    && snapshot.get("updated").getAsLong() >= System.currentTimeMillis() - MAX_AGE) {
                return snapshot;
            }
        } catch (RuntimeException malformed) {
            return null;
        }
        return null;
    }

    synchronized List<Summary> list() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            var summaries = new ArrayList<Summary>();
            for (Path path : files.toList()) {
                UUID id = id(path);
                if (id == null) {
                    continue;
                }
                JsonObject data = read(id);
                if (data != null) {
                    summaries.add(new Summary(id, data.get("updated").getAsLong(), data.get("title").getAsString()));
                }
            }
            return summaries.stream().sorted(Comparator.comparingLong(Summary::updated).reversed()
                    .thenComparing(Summary::id)).toList();
        }
    }

    synchronized void prune() throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            var dated = new ArrayList<Map.Entry<Path, FileTime>>();
            for (Path path : files.toList()) {
                if (id(path) != null && Files.isRegularFile(path)) {
                    dated.add(Map.entry(path, Files.getLastModifiedTime(path)));
                }
            }
            dated.sort(Map.Entry.<Path, FileTime>comparingByValue().reversed()
                    .thenComparing(Map.Entry::getKey));
            long oldest = System.currentTimeMillis() - MAX_AGE;
            for (int index = 0; index < dated.size(); index++) {
                var entry = dated.get(index);
                if (index >= MAX_SESSIONS || entry.getValue().toMillis() < oldest) {
                    Files.deleteIfExists(entry.getKey());
                }
            }
        }
    }

    private Path file(UUID id) {
        return directory.resolve(id + ".json");
    }

    private static UUID id(Path path) {
        String name = path.getFileName().toString();
        if (name.length() == 41 && name.endsWith(".json")) {
            try {
                UUID id = UUID.fromString(name.substring(0, 36));
                return name.equals(id + ".json") ? id : null;
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return null;
    }
}
