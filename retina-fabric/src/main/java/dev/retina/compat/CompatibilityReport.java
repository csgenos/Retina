/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * The combined outcome of every startup compatibility check.
 *
 * <p>Collected into one object rather than thrown one at a time so that a user with several
 * problems sees all of them at once. Fixing one problem only to hit the next on the following
 * launch is the worst version of this experience.
 */
public record CompatibilityReport(List<ModConflicts.Conflict> conflicts,
                                  SodiumCompatibility.Result sodium) {

    public CompatibilityReport {
        conflicts = List.copyOf(conflicts);
    }

    /** A placeholder used before pre-launch has run. */
    public static CompatibilityReport notRunYet() {
        return new CompatibilityReport(List.of(),
            new SodiumCompatibility.Result.Ok("<not checked>"));
    }

    /** Builds a report. */
    public static CompatibilityReport of(List<ModConflicts.Conflict> conflicts,
                                         SodiumCompatibility.Result sodium) {
        return new CompatibilityReport(conflicts, sodium);
    }

    /** Whether Retina must refuse to start. */
    public boolean isFatal() {
        return !(sodium instanceof SodiumCompatibility.Result.Ok)
            || conflicts.stream().anyMatch(
                c -> c.severity() == ModConflicts.Conflict.Severity.FATAL);
    }

    /** The full message shown on the pre-launch error screen. */
    public String fatalMessage() {
        StringBuilder out = new StringBuilder("Retina cannot start with this mod set.\n\n");
        switch (sodium) {
            case SodiumCompatibility.Result.Missing missing ->
                out.append(missing.message()).append("\n\n");
            case SodiumCompatibility.Result.UnsupportedVersion unsupported ->
                out.append(unsupported.message()).append("\n\n");
            case SodiumCompatibility.Result.Ok ignored -> {
                // Nothing to report about Sodium.
            }
        }
        for (ModConflicts.Conflict conflict : conflicts) {
            if (conflict.severity() != ModConflicts.Conflict.Severity.FATAL) {
                continue;
            }
            out.append(conflict.displayName()).append(' ').append(conflict.version())
                .append(":\n").append(conflict.reason()).append("\n\n");
        }
        out.append("Remove one of the conflicting mods, or remove Retina, and start again.");
        return out.toString();
    }

    /** Informational lines for the log. */
    public List<String> notes() {
        List<String> out = new ArrayList<>();
        if (sodium instanceof SodiumCompatibility.Result.Ok ok) {
            out.add("Sodium " + ok.version() + " detected and supported");
        }
        return List.copyOf(out);
    }

    /** Non-fatal warnings for the log and the renderer settings screen. */
    public List<String> warnings() {
        return conflicts.stream()
            .filter(c -> c.severity() == ModConflicts.Conflict.Severity.WARNING)
            .map(c -> c.displayName() + " " + c.version() + ": " + c.reason())
            .toList();
    }
}
