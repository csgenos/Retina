/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.pack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * An in-memory {@link PackSource}, used by the clean-room test fixtures and by the
 * emergency fallback pipeline.
 *
 * <p>Having a third implementation keeps the interface honest: anything that only works for
 * directories or only for ZIPs shows up immediately in the unit tests.
 */
public final class MemoryPackSource implements PackSource {
    private final String name;
    private final Map<PackPath, byte[]> files = new LinkedHashMap<>();
    private final TreeSet<PackPath> directories = new TreeSet<>();

    public MemoryPackSource(String name) {
        this.name = name;
        directories.add(PackPath.root());
    }

    /** Adds a UTF-8 text file. */
    public MemoryPackSource put(String path, String content) {
        return put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Adds a binary file. */
    public MemoryPackSource put(String path, byte[] content) {
        PackPath p = PackPath.of(path);
        files.put(p, content.clone());
        PackPath dir = p.parent();
        while (!dir.isRoot() && directories.add(dir)) {
            dir = dir.parent();
        }
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean exists(PackPath path) {
        return files.containsKey(path);
    }

    @Override
    public boolean isDirectory(PackPath path) {
        return directories.contains(path);
    }

    @Override
    public Optional<byte[]> read(PackPath path) {
        return Optional.ofNullable(files.get(path)).map(byte[]::clone);
    }

    @Override
    public List<PackPath> list(PackPath directory) {
        List<PackPath> out = new ArrayList<>();
        for (PackPath candidate : files.keySet()) {
            if (candidate.parent().equals(directory)) {
                out.add(candidate);
            }
        }
        for (PackPath candidate : directories) {
            if (!candidate.isRoot() && candidate.parent().equals(directory)) {
                out.add(candidate);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    @Override
    public String contentHash() {
        MessageDigest digest = DirectoryPackSource.sha256();
        List<PackPath> sorted = new ArrayList<>(files.keySet());
        sorted.sort(Comparator.naturalOrder());
        for (PackPath path : sorted) {
            digest.update(path.toEntryName().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(files.get(path));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override
    public void close() {
    }
}
