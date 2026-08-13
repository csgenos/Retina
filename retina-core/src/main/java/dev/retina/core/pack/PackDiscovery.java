/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Enumerates candidate shader packs in the {@code shaderpacks/} directory.
 *
 * <p>Discovery is deliberately shallow and cheap: it must run every time the selection
 * screen opens without stalling the client, so it inspects names and, for ZIPs, only the
 * archive header. Whether a pack is actually loadable is decided later by
 * {@link PackLoader}, because that requires reading {@code shaders/}.
 */
public final class PackDiscovery {
    private final Path shaderpacksDirectory;

    public PackDiscovery(Path shaderpacksDirectory) {
        this.shaderpacksDirectory = shaderpacksDirectory;
    }

    /** A pack found on disk, before it has been opened or validated. */
    public record Candidate(String name, Path path, Kind kind) {
        public enum Kind { DIRECTORY, ZIP }
    }

    /** Creates {@code shaderpacks/} if it does not exist. */
    public void ensureDirectory() throws IOException {
        Files.createDirectories(shaderpacksDirectory);
    }

    /** The directory being scanned. */
    public Path directory() {
        return shaderpacksDirectory;
    }

    /**
     * Lists candidate packs, sorted case-insensitively by name.
     *
     * <p>Never throws for an individual unreadable entry — a pack that cannot be inspected
     * is simply not offered, and the rest of the list still works.
     */
    public List<Candidate> discover() throws IOException {
        if (!Files.isDirectory(shaderpacksDirectory)) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(shaderpacksDirectory)) {
            for (Path entry : entries.toList()) {
                String fileName = entry.getFileName().toString();
                if (fileName.startsWith(".")) {
                    continue;
                }
                try {
                    if (Files.isDirectory(entry)) {
                        out.add(new Candidate(fileName, entry, Candidate.Kind.DIRECTORY));
                    } else if (Files.isRegularFile(entry) && isZipName(fileName)
                        && ZipPackSource.looksLikeZip(entry)) {
                        out.add(new Candidate(fileName, entry, Candidate.Kind.ZIP));
                    }
                } catch (RuntimeException ignored) {
                    // An entry that cannot even be stat'ed is not a usable pack.
                }
            }
        }
        out.sort(Comparator.comparing(c -> c.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static boolean isZipName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip");
    }

    /** Opens a candidate for reading. The caller owns the returned source. */
    public static PackSource open(Candidate candidate) throws IOException {
        return switch (candidate.kind()) {
            case DIRECTORY -> new DirectoryPackSource(candidate.path(), candidate.name());
            case ZIP -> new ZipPackSource(candidate.path(), candidate.name());
        };
    }
}
