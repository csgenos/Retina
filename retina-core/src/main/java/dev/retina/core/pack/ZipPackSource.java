/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.pack;

import dev.retina.core.RetinaLimits;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A shader pack stored as a ZIP archive under {@code shaderpacks/}.
 *
 * <p>The archive is opened once and its directory is indexed eagerly so that path lookup is
 * a hash probe rather than a scan. Entry names are normalised through {@link PackPath},
 * which is what makes archive traversal ({@code ../../..} entries, absolute names, drive
 * letters, NUL injection) impossible rather than merely unlikely: an entry whose name is not
 * a safe pack-relative path is dropped from the index and is therefore unreadable.
 *
 * <p>Decompression bombs are handled by three independent bounds — entry count, per-entry
 * compression ratio, and total uncompressed bytes — checked while the index is built and
 * again while an entry is read.
 *
 * <p>The archive is never modified.
 */
public final class ZipPackSource implements PackSource {
    private final ZipFile zip;
    private final Path file;
    private final String name;
    /** Pack path -> archive entry name, for regular files only. */
    private final Map<PackPath, String> files = new LinkedHashMap<>();
    /** Every directory that has at least one descendant, including implied ones. */
    private final TreeSet<PackPath> directories = new TreeSet<>();
    private final List<String> rejectedEntries = new ArrayList<>();

    public ZipPackSource(Path file, String name) throws IOException {
        this.file = file;
        this.name = name;
        this.zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8);
        try {
            index();
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
    }

    private void index() throws IOException {
        int count = 0;
        long totalUncompressed = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (++count > RetinaLimits.MAX_ZIP_ENTRIES) {
                throw new IOException(name + " contains more than "
                    + RetinaLimits.MAX_ZIP_ENTRIES + " entries");
            }
            String raw = entry.getName();
            PackPath path;
            try {
                path = PackPath.of(raw);
            } catch (PackPath.PackPathException e) {
                rejectedEntries.add(raw + " (" + e.getMessage() + ")");
                continue;
            }
            if (path.isRoot()) {
                continue;
            }
            if (entry.isDirectory()) {
                addDirectoryChain(path);
                continue;
            }
            long size = entry.getSize();
            long compressed = entry.getCompressedSize();
            if (size >= 0) {
                totalUncompressed += size;
                if (totalUncompressed > RetinaLimits.MAX_ZIP_UNCOMPRESSED_BYTES) {
                    throw new IOException(name + " expands to more than "
                        + RetinaLimits.MAX_ZIP_UNCOMPRESSED_BYTES + " bytes");
                }
                if (compressed > 0 && size / compressed > RetinaLimits.MAX_ZIP_COMPRESSION_RATIO) {
                    throw new IOException(name + " entry " + raw + " has a compression ratio of "
                        + (size / compressed) + ":1, which exceeds the "
                        + RetinaLimits.MAX_ZIP_COMPRESSION_RATIO + ":1 limit");
                }
            }
            files.put(path, raw);
            addDirectoryChain(path.parent());
        }
    }

    private void addDirectoryChain(PackPath directory) {
        PackPath current = directory;
        while (!current.isRoot() && directories.add(current)) {
            current = current.parent();
        }
        directories.add(PackPath.root());
    }

    /**
     * Entry names that were refused during indexing, for diagnostics.
     *
     * <p>Surfaced in the pack error UI so that a pack built with a broken zipper reports
     * <em>which</em> entries were dropped rather than mysteriously missing files.
     */
    public List<String> rejectedEntries() {
        return List.copyOf(rejectedEntries);
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
    public Optional<byte[]> read(PackPath path) throws IOException {
        String entryName = files.get(path);
        if (entryName == null) {
            return Optional.empty();
        }
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return Optional.empty();
        }
        long declared = entry.getSize();
        if (declared > RetinaLimits.MAX_SOURCE_FILE_BYTES) {
            throw new IOException(path + " declares " + declared + " bytes, over the "
                + RetinaLimits.MAX_SOURCE_FILE_BYTES + " byte limit");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            // Read with a hard cap rather than trusting the declared size: a crafted archive
            // can understate it, and readAllBytes() would then allocate without bound.
            byte[] data = in.readNBytes(RetinaLimits.MAX_SOURCE_FILE_BYTES + 1);
            if (data.length > RetinaLimits.MAX_SOURCE_FILE_BYTES) {
                throw new IOException(path + " exceeds the "
                    + RetinaLimits.MAX_SOURCE_FILE_BYTES + " byte limit while decompressing");
            }
            return Optional.of(data);
        }
    }

    @Override
    public List<PackPath> list(PackPath directory) {
        if (!directories.contains(directory)) {
            return List.of();
        }
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
    public String contentHash() throws IOException {
        MessageDigest digest = DirectoryPackSource.sha256();
        List<PackPath> sorted = new ArrayList<>(files.keySet());
        sorted.sort(Comparator.naturalOrder());
        for (PackPath path : sorted) {
            digest.update(path.toEntryName().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            ZipEntry entry = zip.getEntry(files.get(path));
            long crc = entry == null ? -1 : entry.getCrc();
            long size = entry == null ? -1 : entry.getSize();
            // CRC + size is enough to detect content change and avoids decompressing the
            // whole archive on every pack listing.
            digest.update(Long.toString(crc).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(Long.toString(size).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The archive on disk, for diagnostics only. */
    public Path file() {
        return file;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    /** Whether {@code path} looks like a ZIP shader pack by magic number. */
    public static boolean looksLikeZip(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] magic = in.readNBytes(4);
            return magic.length == 4 && magic[0] == 'P' && magic[1] == 'K'
                && (magic[2] == 3 || magic[2] == 5 || magic[2] == 7);
        } catch (IOException e) {
            return false;
        }
    }
}
