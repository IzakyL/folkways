package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class Guide {

    public record Chapter(String slug, String title, String body) {
    }

    private static final List<Chapter> ENGLISH = load("en_us");
    private static final List<Chapter> CHINESE = load("zh_cn");

    private static final List<String> PART_DOCUMENTS = List.of("core", "create", "cbc", "letitwalk", "transport",
            "warfare", "storage", "cookery");
    private static final Map<String, Chapter> ENGLISH_PARTS = loadParts("en_us");
    private static final Map<String, Chapter> CHINESE_PARTS = loadParts("zh_cn");

    private Guide() {
    }

    static String help(String name, String language) {
        List<Chapter> chapters = localized(ENGLISH, chinese(language) ? CHINESE : List.of());
        if (name.equals("guide")) {
            return chapters.stream().map(chapter -> "guide:" + chapter.slug() + " — " + chapter.title())
                    .reduce((before, after) -> before + "\n" + after).orElse("");
        }
        String slug = name.substring("guide:".length());
        return chapters.stream().filter(chapter -> chapter.slug().equals(slug))
                .map(chapter -> "## " + chapter.title() + "\n\n" + chapter.body()).findFirst()
                .orElse("Unknown guide chapter: " + slug + "; use help(\"guide\")");
    }

    static String partHelp(String part, String language) {
        Chapter chapter = part(part, language);
        return chapter == null ? "Unknown part: " + part : "## " + chapter.title() + "\n\n" + chapter.body();
    }

    static JsonObject parts(Stream<JsonObject> controls, String language) {
        JsonObject summaries = new JsonObject();
        controls.map(control -> control.get("part").getAsString()).distinct().forEach(id -> {
            Chapter chapter = part(id, language);
            summaries.addProperty(id, chapter == null ? "No packaged part guide." : chapter.body().lines().findFirst()
                    .orElseThrow());
        });
        return summaries;
    }

    static Stream<JsonObject> rows(JsonArray array) {
        return array.asList().stream().map(element -> element.getAsJsonObject());
    }

    public static boolean hasPart(String id, String language) {
        return findPart(id, chinese(language) ? CHINESE_PARTS : ENGLISH_PARTS) != null;
    }

    private static Chapter part(String id, String language) {
        if (chinese(language)) {
            Chapter chapter = findPart(id, CHINESE_PARTS);
            if (chapter != null) {
                return chapter;
            }
        }
        return findPart(id, ENGLISH_PARTS);
    }

    private static boolean chinese(String language) {
        return language.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private static Chapter findPart(String id, Map<String, Chapter> chapters) {
        String topic = "parts/" + id;
        Chapter exact = chapters.get(topic);
        if (exact != null) {
            return exact;
        }
        int slash = id.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        String suffix = id.substring(slash + 1);
        String prefix = topic.substring(0, "parts/".length() + slash + 1);
        if (suffix.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            return chapters.get(prefix + "<uuid>");
        }
        if (suffix.matches("[1-9][0-9]*")) {
            return chapters.get(prefix + "<n>");
        }
        return null;
    }

    private static Map<String, Chapter> loadParts(String language) {
        Map<String, Chapter> chapters = new LinkedHashMap<>();
        for (String document : PART_DOCUMENTS) {
            for (Chapter chapter : split(resource(language, "parts/" + document))) {
                if (chapter.body().isBlank() || chapters.putIfAbsent(chapter.slug(), chapter) != null) {
                    throw new IllegalStateException("Invalid part guide entry: " + language + "/" + chapter.slug());
                }
            }
        }
        return Map.copyOf(chapters);
    }

    public static List<Chapter> localized(List<Chapter> english, List<Chapter> translated) {
        Map<String, Chapter> chapters = new LinkedHashMap<>();
        english.forEach(chapter -> chapters.put(chapter.slug(), chapter));
        translated.stream().filter(chapter -> !chapter.body().isBlank())
                .forEach(chapter -> chapters.put(chapter.slug(), chapter));
        return List.copyOf(chapters.values());
    }

    public static List<Chapter> split(String markdown) {
        List<Chapter> chapters = new ArrayList<>();
        String title = null;
        String slug = null;
        String fence = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                String marker = stripped.substring(0, 3);
                fence = fence == null ? marker : fence.equals(marker) ? null : fence;
            }
            if (fence == null && line.startsWith("## ")) {
                if (title != null) {
                    chapters.add(new Chapter(slug, title, body.toString().strip()));
                }
                title = line.substring(3).strip();
                slug = title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s-]", "")
                        .replaceAll("\\s+", "-");
                body.setLength(0);
            } else if (fence == null && title != null && line.startsWith("<!-- guide:") && line.endsWith(" -->")) {
                slug = line.substring("<!-- guide:".length(), line.length() - " -->".length()).strip();
            } else if (title != null) {
                body.append(line).append('\n');
            }
        }
        if (title != null) {
            chapters.add(new Chapter(slug, title, body.toString().strip()));
        }
        return List.copyOf(chapters);
    }

    private static List<Chapter> load(String language) {
        List<Chapter> chapters = new ArrayList<>(split(resource(language, "gameplay")));
        chapters.addAll(split(resource(language, "tools")));
        return List.copyOf(chapters);
    }

    private static String resource(String language, String document) {
        String path = "/assets/folkways_assistant/guide/" + language + "/" + document + ".md";
        try (var stream = Guide.class.getResourceAsStream(path)) {
            return stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read packaged gameplay guide " + path, failure);
        }
    }
}
