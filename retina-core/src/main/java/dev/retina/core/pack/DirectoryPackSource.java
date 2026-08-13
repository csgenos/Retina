/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.pack;

import dev.retina.core.RetinaLimits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** A shader pack stored as a plain directory under {@code shaderpacks/}. */
public final class DirectoryPackSource implements PackSource {
    private final Path root;
    private final String name;

    public DirectoryPackSource(Path root, String name) throws IOException {
        // Resolve symlinks once, up front: every later containment check compares against
        // this real path, so a symlink planted inside the pack cannot widen access.
        this.root = root.toRealPath();
        this.name = name;
        if (!Files.isDirectory(this.root)) {
            throw new IOException("not a directory: " + root);
        }
    }

    /**
     * Maps a pack path onto the filesystem, refusing anything that leaves the pack root.
     *
     * <p>{@link PackPath} already guarantees no {@code ..} escapes, but a symlink inside the
     * directory can still point outside it, so the resolved real path is re-checked. Missing
     * files cannot be resolved to a real path, so those are checked lexically instead — a
     * missing path is reported as absent by the caller either way.
     */
    private Optional<Path> resolve(PackPath path) {
        Path candidate = root;
        for (String segment : path.segments()) {
            candidate = candidate.resolve(segment);
        }
        Path normalised = candidate.normalize();
        if (!normalised.startsWith(root)) {
            return Optional.empty();
        }
        if (!Files.exists(normalised, LinkOption.NOFOLLOW_LINKS)) {
            return Files.exists(normalised) ? checkReal(normalised) : Optional.of(normalised);
        }
        return checkReal(normalised);
    }

    private Optional<Path> checkReal(Path normalised) {
        try {
            return normalised.toRealPath().startsWith(root)
                ? Optional.of(normalised)
                : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean exists(PackPath path) {
        return resolve(path).filter(Files::isRegularFile).isPresent();
    }

    @Override
    public boolean isDirectory(PackPath path) {
        return resolve(path).filter(Files::isDirectory).isPresent();
    }

    @Override
    public Optional<byte[]> read(PackPath path) throws IOException {
        Optional<Path> resolved = resolve(path);
        if (resolved.isEmpty() || !Files.isRegularFile(resolved.get())) {
            return Optional.empty();
        }
        Path file = resolved.get();
        long size = Files.size(file);
        if (size > RetinaLimits.MAX_SOURCE_FILE_BYTES) {
            throw new IOException(path + " is " + size + " bytes, over the "
                + RetinaLimits.MAX_SOURCE_FILE_BYTES + " byte limit");
        }
        return Optional.of(Files.readAllBytes(file));
    }

    @Override
    public List<PackPath> list(PackPath directory) throws IOException {
        Optional<Path> resolved = resolve(directory);
        if (resolved.isEmpty() || !Files.isDirectory(resolved.get())) {
            return List.of();
        }
        List<PackPath> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(resolved.get())) {
            for (Path child : children.toList()) {
                try {
                    out.add(directory.resolve(child.getFileName().toString()));
                } catch (PackPath.PackPathException ignored) {
                    // A file whose name cannot be a safe pack path is simply not part of the
                    // pack; skipping it is correct and keeps listing total.
                }
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    @Override
    public String contentHash() throws IOException {
        MessageDigest digest = sha256();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        // Sort by the pack-relative name so the hash is independent of filesystem order.
        files.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));
        for (Path file : files) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            digest.update(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try {
                digest.update(Files.readAllBytes(file));
            } catch (IOException e) {
                // An unreadable file still contributes its name, so the hash changes if it
                // later becomes readable.
                digest.update("<unreadable>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    @Override
    public void close() {
        // Nothing is held open; reads are per-call.
    }
}
