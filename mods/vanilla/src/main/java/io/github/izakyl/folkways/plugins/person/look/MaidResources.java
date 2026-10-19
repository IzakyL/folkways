package io.github.izakyl.folkways.plugins.person.look;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

/** A read-only maid pack; archives are read in place and never extracted. */
public record MaidResources(Path root, boolean archive) {
    private static String checked(String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0
                || relative.startsWith("/") || relative.matches("^[A-Za-z]:.*")) {
            throw new IOException("Invalid pack path: " + relative);
        }
        for (String part : relative.split("/", -1)) {
            if (part.equals("..") || part.equals(".") || part.isEmpty()) {
                throw new IOException("Invalid pack path: " + relative);
            }
        }
        return relative;
    }

    public byte[] bytes(String relative) throws IOException {
        String name = checked(relative);
        if (archive) {
            try (ZipFile zip = new ZipFile(root.toFile())) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory()) {
                    throw new IOException("Missing pack resource: " + name);
                }
                try (var input = zip.getInputStream(entry)) {
                    return input.readAllBytes();
                }
            }
        }
        Path file = root.resolve(name);
        if (!file.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Pack resource escapes its root: " + name);
        }
        return Files.readAllBytes(file);
    }

    public JsonObject json(String relative) throws IOException {
        return JsonParser.parseString(new String(bytes(relative), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    public boolean contains(String relative) {
        try {
            String name = checked(relative);
            if (archive) {
                try (ZipFile zip = new ZipFile(root.toFile())) {
                    ZipEntry entry = zip.getEntry(name);
                    return entry != null && !entry.isDirectory();
                }
            }
            Path file = root.resolve(name);
            return Files.isRegularFile(file) && file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException exception) {
            return false;
        }
    }

    List<String> files() throws IOException {
        if (archive) {
            try (ZipFile zip = new ZipFile(root.toFile())) {
                return zip.stream().filter(entry -> !entry.isDirectory()).map(ZipEntry::getName).sorted().toList();
            }
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).map(root::relativize)
                .map(path -> path.toString().replace('\\', '/')).sorted().toList();
        }
    }

    public Map<String, JsonObject> languages() throws IOException {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (String file : files()) {
            if (!file.matches("(?:[^/]+/)?assets/[^/]+/lang/[^/]+\\.(json|lang)")) {
                continue;
            }
            String name = file.substring(file.lastIndexOf('/') + 1, file.lastIndexOf('.'));
            try {
                JsonObject language = json(file);
                JsonObject merged = result.computeIfAbsent(name, ignored -> new JsonObject());
                language.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue()));
            } catch (IOException | RuntimeException ignored) {
                // A broken translation must not hide the pack's remaining languages.
            }
        }
        return result;
    }
}
