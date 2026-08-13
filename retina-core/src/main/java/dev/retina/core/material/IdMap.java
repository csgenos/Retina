/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.material;

import dev.retina.core.props.OrderedProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A pack's {@code block.properties}, {@code item.properties} or {@code entity.properties}.
 *
 * <p>These files map game registry entries onto small integer ids that shaders read through
 * {@code blockEntityId}, {@code entityId} and {@code currentRenderedItemId}. The format is
 * {@code <prefix>.<id> = <entry> <entry> ...}, where each entry is a namespaced id optionally
 * followed by state predicates:
 *
 * <pre>
 *   block.31 = minecraft:grass_block minecraft:dirt
 *   block.40 = minecraft:oak_leaves:persistent=false
 *   block.41 = #minecraft:logs
 *   entity.10 = minecraft:creeper
 *   item.201 = minecraft:torch minecraft:soul_torch
 * </pre>
 *
 * <p>Ids are deliberately taken from the pack, never allocated by Retina. A pack's shader
 * source contains literal comparisons against these numbers, so an id that shifted between
 * reloads — or between machines — would change what the pack draws. Unknown entries are kept
 * in the map and reported rather than dropped, so that a pack referencing a modded block that
 * is not installed still works for the blocks that are.
 */
public final class IdMap {

    /** Which file a map came from. */
    public enum Kind {
        BLOCK("block"),
        ITEM("item"),
        ENTITY("entity");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        /** The key prefix used in the file, e.g. {@code block}. */
        public String prefix() {
            return prefix;
        }

        /** The file name, e.g. {@code block.properties}. */
        public String fileName() {
            return prefix + ".properties";
        }
    }

    /**
     * One matcher for a registry entry.
     *
     * @param namespace the namespace, defaulting to {@code minecraft}
     * @param path the registry path, or the tag name when {@code tag} is true
     * @param tag whether this entry is a {@code #tag} reference
     * @param predicates blockstate property constraints, all of which must hold
     */
    public record Matcher(String namespace, String path, boolean tag,
                          Map<String, String> predicates) {

        public Matcher {
            predicates = Map.copyOf(predicates);
        }

        /** The canonical {@code namespace:path} form. */
        public String qualifiedName() {
            return namespace + ":" + path;
        }

        /**
         * Whether this matcher applies to a registry entry with the given properties.
         *
         * @param properties the entry's blockstate properties, empty for items and entities
         */
        public boolean matches(String entryNamespace, String entryPath,
                               Map<String, String> properties) {
            if (tag) {
                // Tag membership can only be resolved against the live registry, which the
                // core module has no access to. The Minecraft bridge resolves tags before
                // calling matches(), so a tag matcher reaching here never applies.
                return false;
            }
            if (!namespace.equals(entryNamespace) || !path.equals(entryPath)) {
                return false;
            }
            for (Map.Entry<String, String> predicate : predicates.entrySet()) {
                String actual = properties.get(predicate.getKey());
                if (actual == null || !actual.equals(predicate.getValue())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder();
            if (tag) {
                out.append('#');
            }
            out.append(qualifiedName());
            new TreeMap<>(predicates).forEach((key, value) ->
                out.append(':').append(key).append('=').append(value));
            return out.toString();
        }
    }

    private final Kind kind;
    private final Map<Integer, List<Matcher>> byId;
    private final List<String> problems;

    private IdMap(Kind kind, Map<Integer, List<Matcher>> byId, List<String> problems) {
        this.kind = kind;
        this.byId = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        this.problems = List.copyOf(problems);
    }

    /** An empty map, for a pack that ships none of these files. */
    public static IdMap empty(Kind kind) {
        return new IdMap(kind, Map.of(), List.of());
    }

    /** Parses the file's text. */
    public static IdMap parse(Kind kind, String text) {
        OrderedProperties properties = OrderedProperties.parse(kind.fileName(), text);
        Map<Integer, List<Matcher>> byId = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        properties.problems().forEach(p -> problems.add(p.toString()));

        String prefix = kind.prefix() + ".";
        for (OrderedProperties.Entry entry : properties.entries()) {
            if (!entry.key().startsWith(prefix)) {
                problems.add("line " + entry.line() + ": '" + entry.key()
                    + "' does not start with '" + prefix + "' and was ignored");
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(entry.key().substring(prefix.length()).trim());
            } catch (NumberFormatException e) {
                problems.add("line " + entry.line() + ": '" + entry.key()
                    + "' does not end with an integer id");
                continue;
            }
            List<Matcher> matchers = byId.computeIfAbsent(id, k -> new ArrayList<>());
            for (String token : entry.value().trim().split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                Optional<Matcher> matcher = parseMatcher(token);
                if (matcher.isPresent()) {
                    matchers.add(matcher.get());
                } else {
                    problems.add("line " + entry.line() + ": could not read '" + token
                        + "' as a registry entry");
                }
            }
        }
        return new IdMap(kind, byId, problems);
    }

    /**
     * Parses one entry token.
     *
     * <p>The grammar is ambiguous in one place that matters: {@code a:b:c=d} could be
     * {@code namespace a}, {@code path b}, predicate {@code c=d}, or a path containing
     * colons. It is resolved by treating the first colon as the namespace separator only when
     * what follows contains no {@code =}; everything after the second colon is a predicate.
     * This matches how packs are written in practice.
     */
    public static Optional<Matcher> parseMatcher(String token) {
        String text = token.trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        boolean tag = text.startsWith("#");
        if (tag) {
            text = text.substring(1);
        }

        List<String> parts = new ArrayList<>(List.of(text.split(":")));
        if (parts.isEmpty()) {
            return Optional.empty();
        }

        String namespace = "minecraft";
        String path;
        int predicateStart;
        if (parts.size() >= 2 && !parts.get(1).contains("=")) {
            namespace = parts.get(0);
            path = parts.get(1);
            predicateStart = 2;
        } else {
            path = parts.getFirst();
            predicateStart = 1;
        }
        if (path.isEmpty() || !isValidIdentifier(namespace) || !isValidIdentifier(path)) {
            return Optional.empty();
        }

        Map<String, String> predicates = new LinkedHashMap<>();
        for (int i = predicateStart; i < parts.size(); i++) {
            String predicate = parts.get(i);
            int equals = predicate.indexOf('=');
            if (equals <= 0 || equals == predicate.length() - 1) {
                return Optional.empty();
            }
            predicates.put(predicate.substring(0, equals).trim(),
                predicate.substring(equals + 1).trim());
        }
        return Optional.of(new Matcher(namespace, path, tag, predicates));
    }

    private static boolean isValidIdentifier(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Which file this map came from. */
    public Kind kind() {
        return kind;
    }

    /** The matchers assigned to {@code id}. */
    public List<Matcher> matchersFor(int id) {
        return byId.getOrDefault(id, List.of());
    }

    /** Every id the pack assigned, in declaration order. */
    public List<Integer> ids() {
        return List.copyOf(byId.keySet());
    }

    /** Every matcher, keyed by id. */
    public Map<Integer, List<Matcher>> byId() {
        return byId;
    }

    /** Whether the pack assigned any ids. */
    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** Problems found while parsing, shown in the pack diagnostics UI. */
    public List<String> problems() {
        return problems;
    }

    /**
     * Resolves a registry entry to its pack id.
     *
     * <p>Matchers are tested in declaration order and the first match wins, which is what
     * lets a pack write a broad rule followed by a narrower override.
     *
     * @return the id, or empty when nothing matched
     */
    public Optional<Integer> resolve(String namespace, String path,
                                     Map<String, String> properties) {
        String ns = namespace.toLowerCase(Locale.ROOT);
        String p = path.toLowerCase(Locale.ROOT);
        for (Map.Entry<Integer, List<Matcher>> entry : byId.entrySet()) {
            for (Matcher matcher : entry.getValue()) {
                if (matcher.matches(ns, p, properties)) {
                    return Optional.of(entry.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A canonical fingerprint for cache keying.
     *
     * <p>Sorted by id so that two files differing only in declaration order produce the same
     * key; the resolution order within one id is preserved because it is semantically
     * significant.
     */
    public String cacheFingerprint() {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(byId).forEach((id, matchers) -> {
            out.append(kind.prefix()).append('.').append(id).append('=');
            matchers.forEach(m -> out.append(m).append(' '));
            out.append('\n');
        });
        return out.toString();
    }
}
