/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.props;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@code .properties} reader that preserves declaration order and reports line numbers.
 *
 * <p>{@link java.util.Properties} is unsuitable for shader packs for three reasons, all of
 * which change observable behaviour:
 *
 * <ul>
 *   <li>it is unordered, but pack semantics depend on order — {@code screen.X} entries lay
 *       out top to bottom, and later {@code program.*} entries override earlier ones;
 *   <li>it decodes {@code \\uXXXX} escapes and treats {@code :} and {@code =} interchangeably
 *       in ways that mangle values such as {@code blend.composite1 = SRC_ALPHA ONE_MINUS_SRC_ALPHA};
 *   <li>it discards line numbers, so a malformed entry cannot be reported against its source
 *       location.
 * </ul>
 *
 * <p>This parser therefore implements only what the shader-pack format actually uses:
 * {@code key=value} and {@code key value}, {@code #} and {@code //} comments, backslash line
 * continuation, and blank-line tolerance.
 */
public final class OrderedProperties {
    /** One parsed entry, with the 1-based line it started on. */
    public record Entry(String key, String value, int line) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> byKey = new LinkedHashMap<>();
    private final List<Problem> problems = new ArrayList<>();
    private final String sourceName;

    /** A recoverable defect found while parsing, surfaced in the pack diagnostics UI. */
    public record Problem(String sourceName, int line, String message) {
        @Override
        public String toString() {
            return sourceName + ":" + line + ": " + message;
        }
    }

    private OrderedProperties(String sourceName) {
        this.sourceName = sourceName;
    }

    /**
     * Parses {@code text}.
     *
     * <p>Parsing never throws for malformed content: a shader pack with one broken line
     * should still load with the rest of its configuration intact, and the broken line is
     * reported through {@link #problems()}. Refusing the whole pack over a stray character
     * would be worse than the format's own tolerance.
     */
    public static OrderedProperties parse(String sourceName, String text) {
        OrderedProperties out = new OrderedProperties(sourceName);
        List<String> lines = text.lines().toList();
        int i = 0;
        while (i < lines.size()) {
            int startLine = i + 1;
            String raw = lines.get(i);
            i++;

            // Line continuation: a trailing backslash joins the next physical line. The
            // backslash is dropped and leading whitespace of the continuation is trimmed,
            // matching how packs format long `screen` and `sliders` lists.
            StringBuilder joined = new StringBuilder(raw);
            while (endsWithContinuation(joined) && i < lines.size()) {
                joined.setLength(joined.length() - 1);
                joined.append(lines.get(i).stripLeading());
                i++;
            }

            String line = stripComment(joined.toString()).trim();
            if (line.isEmpty()) {
                continue;
            }

            int eq = line.indexOf('=');
            String key;
            String value;
            if (eq >= 0) {
                key = line.substring(0, eq).trim();
                value = line.substring(eq + 1).trim();
            } else {
                // `key value` form, used by some packs for `sliders` and `screen`.
                int space = indexOfWhitespace(line);
                if (space < 0) {
                    key = line;
                    value = "";
                } else {
                    key = line.substring(0, space).trim();
                    value = line.substring(space + 1).trim();
                }
            }

            if (key.isEmpty()) {
                out.problems.add(new Problem(sourceName, startLine, "entry has an empty key"));
                continue;
            }
            Entry entry = new Entry(key, value, startLine);
            out.entries.add(entry);
            Entry previous = out.byKey.put(key, entry);
            if (previous != null) {
                out.problems.add(new Problem(sourceName, startLine,
                    "duplicate key '" + key + "' overrides the value from line "
                        + previous.line()));
            }
        }
        return out;
    }

    private static boolean endsWithContinuation(CharSequence text) {
        if (text.isEmpty() || text.charAt(text.length() - 1) != '\\') {
            return false;
        }
        // An even number of trailing backslashes is an escaped backslash, not a continuation.
        int backslashes = 0;
        for (int i = text.length() - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /**
     * Removes a trailing comment.
     *
     * <p>{@code #} always starts a comment. {@code //} starts one only when it is not part of
     * a value that legitimately contains a slash pair; in practice no shader-pack property
     * value contains {@code //}, and packs do use {@code //} comments, so it is treated as a
     * comment introducer.
     */
    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        int slashes = line.indexOf("//");
        int cut = -1;
        if (hash >= 0) {
            cut = hash;
        }
        if (slashes >= 0 && (cut < 0 || slashes < cut)) {
            cut = slashes;
        }
        return cut < 0 ? line : line.substring(0, cut);
    }

    private static int indexOfWhitespace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** All entries in declaration order, including duplicates. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Problems found while parsing. */
    public List<Problem> problems() {
        return List.copyOf(problems);
    }

    /** The name used in diagnostics, e.g. {@code shaders.properties}. */
    public String sourceName() {
        return sourceName;
    }

    /** The last value declared for {@code key}. */
    public Optional<String> get(String key) {
        return Optional.ofNullable(byKey.get(key)).map(Entry::value);
    }

    /** The line on which {@code key} was last declared. */
    public int lineOf(String key) {
        Entry entry = byKey.get(key);
        return entry == null ? -1 : entry.line();
    }

    /** Whether {@code key} was declared at all. */
    public boolean has(String key) {
        return byKey.containsKey(key);
    }

    /** Entries whose key starts with {@code prefix}, in declaration order. */
    public List<Entry> withPrefix(String prefix) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.key().startsWith(prefix)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** Parses {@code key} as a boolean, accepting the {@code on}/{@code off} spelling. */
    public Optional<Boolean> getBoolean(String key) {
        return get(key).flatMap(v -> switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "on", "1", "yes" -> Optional.of(Boolean.TRUE);
            case "false", "off", "0", "no" -> Optional.of(Boolean.FALSE);
            default -> {
                problems.add(new Problem(sourceName, lineOf(key),
                    "'" + key + "' expects true/false but found '" + v + "'"));
                yield Optional.empty();
            }
        });
    }

    /** Parses {@code key} as an integer. */
    public Optional<Integer> getInt(String key) {
        return get(key).flatMap(v -> {
            try {
                return Optional.of(Integer.parseInt(v.trim()));
            } catch (NumberFormatException e) {
                problems.add(new Problem(sourceName, lineOf(key),
                    "'" + key + "' expects an integer but found '" + v + "'"));
                return Optional.empty();
            }
        });
    }

    /** Parses {@code key} as a float. */
    public Optional<Float> getFloat(String key) {
        return get(key).flatMap(v -> {
            try {
                return Optional.of(Float.parseFloat(v.trim()));
            } catch (NumberFormatException e) {
                problems.add(new Problem(sourceName, lineOf(key),
                    "'" + key + "' expects a number but found '" + v + "'"));
                return Optional.empty();
            }
        });
    }

    /** Splits {@code key}'s value on whitespace. */
    public List<String> getList(String key) {
        return get(key)
            .map(v -> v.isBlank() ? List.<String>of() : List.of(v.trim().split("\\s+")))
            .orElse(List.of());
    }

    /** Records a problem discovered by a consumer of this file. */
    public void addProblem(int line, String message) {
        problems.add(new Problem(sourceName, line, message));
    }
}
