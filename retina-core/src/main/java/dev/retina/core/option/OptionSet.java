/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.option;

import dev.retina.core.pack.PackPath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every option a pack declares, indexed for lookup and for source rewriting.
 *
 * <p>The declaration index is keyed by {@code (file, line)} because applying an option means
 * rewriting the exact line it was declared on. Doing it by name with a textual search would
 * also hit unrelated occurrences of the name inside expressions and comments.
 */
public final class OptionSet {
    private final Map<String, PackOption> byName;
    private final Map<PackPath, Map<Integer, PackOption>> byDeclaration;
    private final List<String> problems;

    private OptionSet(Map<String, PackOption> byName,
                      Map<PackPath, Map<Integer, PackOption>> byDeclaration,
                      List<String> problems) {
        this.byName = Map.copyOf(byName);
        this.byDeclaration = byDeclaration;
        this.problems = List.copyOf(problems);
    }

    /** An empty set, used when a pack declares no options. */
    public static OptionSet empty() {
        return new OptionSet(Map.of(), Map.of(), List.of());
    }

    /** Builds an indexed set from a scan. */
    public static OptionSet from(OptionScanner.Result scan) {
        Map<PackPath, Map<Integer, PackOption>> byDeclaration = new LinkedHashMap<>();
        scan.options().values().forEach(option -> byDeclaration
            .computeIfAbsent(option.declaration().file(), f -> new LinkedHashMap<>())
            .put(option.declaration().line(), option));
        return new OptionSet(scan.options(), byDeclaration, scan.problems());
    }

    /** All options, keyed by name. */
    public Map<String, PackOption> byName() {
        return byName;
    }

    /** Looks up one option. */
    public Optional<PackOption> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** The option declared at {@code file:line}, if any. */
    public Optional<PackOption> declaredAt(PackPath file, int line) {
        Map<Integer, PackOption> inFile = byDeclaration.get(file);
        return inFile == null ? Optional.empty() : Optional.ofNullable(inFile.get(line));
    }

    /** Whether any option is declared in {@code file}. */
    public boolean touches(PackPath file) {
        return byDeclaration.containsKey(file);
    }

    /** Names in declaration order. */
    public List<String> names() {
        return new ArrayList<>(byName.keySet());
    }

    /** Non-fatal problems found during scanning. */
    public List<String> problems() {
        return problems;
    }

    /** Whether the pack declares no options at all. */
    public boolean isEmpty() {
        return byName.isEmpty();
    }
}
