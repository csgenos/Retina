/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The user's chosen value for each option of one pack.
 *
 * <p>Only values that <em>differ</em> from the pack default are stored. That keeps the saved
 * settings file small, but more importantly it means a pack update that changes a default
 * moves the user with it instead of pinning them to a stale value they never chose.
 *
 * <p>Instances are immutable; {@link #with} returns a new set. The option screen builds a
 * candidate set, the pipeline is compiled against it, and only then is it swapped in, so a
 * half-applied set can never be observed by the renderer.
 */
public final class OptionValues {
    private final Map<String, String> overrides;

    private OptionValues(Map<String, String> overrides) {
        this.overrides = Map.copyOf(overrides);
    }

    /** No overrides: every option sits at its pack default. */
    public static OptionValues defaults() {
        return new OptionValues(Map.of());
    }

    /** Builds a set from stored overrides, dropping entries the pack no longer declares. */
    public static OptionValues of(Map<String, String> stored, OptionSet declared) {
        Map<String, String> kept = new LinkedHashMap<>();
        stored.forEach((name, value) -> {
            Optional<PackOption> option = declared.byName(name);
            if (option.isPresent() && isValid(option.get(), value)) {
                kept.put(name, value);
            }
        });
        return new OptionValues(kept);
    }

    private static boolean isValid(PackOption option, String value) {
        if (option instanceof PackOption.BooleanOption) {
            return value.equals("true") || value.equals("false");
        }
        if (option instanceof PackOption.ValueOption v) {
            return v.allowedValues().contains(value);
        }
        return false;
    }

    /** A copy with {@code name} set to {@code value}. */
    public OptionValues with(String name, String value) {
        Map<String, String> next = new LinkedHashMap<>(overrides);
        next.put(Objects.requireNonNull(name), Objects.requireNonNull(value));
        return new OptionValues(next);
    }

    /** A copy with {@code name} reset to the pack default. */
    public OptionValues without(String name) {
        if (!overrides.containsKey(name)) {
            return this;
        }
        Map<String, String> next = new LinkedHashMap<>(overrides);
        next.remove(name);
        return new OptionValues(next);
    }

    /** A copy with every entry of {@code other} applied on top. */
    public OptionValues withAll(Map<String, String> other) {
        Map<String, String> next = new LinkedHashMap<>(overrides);
        next.putAll(other);
        return new OptionValues(next);
    }

    /** The raw overrides, for persistence. */
    public Map<String, String> overrides() {
        return overrides;
    }

    /** Whether any option has been changed from its default. */
    public boolean isDefault() {
        return overrides.isEmpty();
    }

    /** The effective text value of {@code option}. */
    public String valueOf(PackOption option) {
        String override = overrides.get(option.name());
        return override != null ? override : option.defaultValueText();
    }

    /** The effective state of a toggle. */
    public boolean isEnabled(PackOption.BooleanOption option) {
        return Boolean.parseBoolean(valueOf(option));
    }

    /**
     * A canonical, order-independent string for cache keying.
     *
     * <p>Sorted, so two sets that differ only in insertion order produce the same key and do
     * not cause a spurious recompile.
     */
    public String cacheFingerprint() {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(overrides).forEach((name, value) ->
            out.append(name).append('=').append(value).append('\n'));
        return out.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OptionValues other && other.overrides.equals(overrides);
    }

    @Override
    public int hashCode() {
        return overrides.hashCode();
    }

    @Override
    public String toString() {
        return "OptionValues" + new TreeMap<>(overrides);
    }
}
