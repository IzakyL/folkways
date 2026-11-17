package io.github.izakyl.folkways.core.library;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

// Resource ids keep ordinary names, and carry other names as reversible UTF-8 hex.
public final class FileNames {

    private static final String PREFIX = "__utf8_";

    private FileNames() {
    }

    public static String encode(String segment) {
        return segment.matches("[a-z0-9_.-]*") ? segment
            : PREFIX + HexFormat.of().formatHex(segment.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String segment) {
        if (segment.startsWith(PREFIX)) {
            try {
                String decoded = new String(HexFormat.of().parseHex(segment.substring(PREFIX.length())),
                    StandardCharsets.UTF_8);
                if (!decoded.matches("[a-z0-9_.-]*") && encode(decoded).equals(segment)) {
                    return decoded;
                }
            } catch (IllegalArgumentException malformed) {
                return segment;
            }
        }
        return segment;
    }

    // Dots stay in place so shelf suffixes and same-name manifests still match.
    public static String encodePath(String path) {
        return Arrays.stream(path.split("/", -1)).map(segment -> Arrays.stream(segment.split("\\.", -1))
            .map(FileNames::encode).collect(Collectors.joining("."))).collect(Collectors.joining("/"));
    }

    public static String decodePath(String path) {
        return Arrays.stream(path.split("/", -1)).map(segment -> Arrays.stream(segment.split("\\.", -1))
            .map(FileNames::decode).collect(Collectors.joining("."))).collect(Collectors.joining("/"));
    }
}
