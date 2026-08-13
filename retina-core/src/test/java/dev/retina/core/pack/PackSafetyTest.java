/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Shader packs are untrusted local input; these are the tests that treat them that way. */
class PackSafetyTest {

    @Test
    @DisplayName("a path that walks above the pack root is refused")
    void traversalIsRefused() {
        assertThrows(PackPath.PackPathException.class,
            () -> PackPath.of("/../../etc/passwd"));
        assertThrows(PackPath.PackPathException.class,
            () -> PackPath.of("/shaders/../../secret"));
        assertThrows(PackPath.PackPathException.class,
            () -> PackPath.of("shaders/lib/../../../outside"));
    }

    @Test
    @DisplayName("backslashes are treated as separators, not as literal characters")
    void backslashesCannotBypassTraversalChecks() {
        assertThrows(PackPath.PackPathException.class,
            () -> PackPath.of("shaders\\..\\..\\outside"));
        assertEquals("/shaders/lib/common.glsl",
            PackPath.of("shaders\\lib\\common.glsl").toAbsoluteString());
    }

    @Test
    @DisplayName("drive letters, home references and NUL are refused")
    void absolutePathFormsAreRefused() {
        assertThrows(PackPath.PackPathException.class, () -> PackPath.of("C:/Windows/System32"));
        assertThrows(PackPath.PackPathException.class, () -> PackPath.of("~/.ssh/id_rsa"));
        assertThrows(PackPath.PackPathException.class, () -> PackPath.of("shaders/a\0b"));
    }

    @Test
    @DisplayName("in-root traversal that stays inside the pack is allowed")
    void safeRelativePathsResolve() {
        PackPath includingFile = PackPath.of("/shaders/world0/composite.fsh");
        assertEquals("/shaders/lib/common.glsl",
            includingFile.parent().resolve("../lib/common.glsl").toAbsoluteString());
    }

    @Test
    @DisplayName("a ZIP entry that escapes the archive root is dropped, not read")
    void zipTraversalEntriesAreDropped(@TempDir Path dir) throws IOException {
        Path archive = dir.resolve("evil.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(zip, "shaders/composite.fsh", "// legitimate");
            write(zip, "../../../../etc/retina-owned", "pwned");
            write(zip, "shaders/../../escape.txt", "pwned");
        }
        try (ZipPackSource source = new ZipPackSource(archive, "evil.zip")) {
            assertTrue(source.exists(PackPath.of("/shaders/composite.fsh")));
            assertEquals(2, source.rejectedEntries().size(),
                () -> "expected both traversal entries to be rejected, got "
                    + source.rejectedEntries());
            // Nothing outside the pack is reachable through any name.
            assertFalse(source.exists(PackPath.of("/etc/retina-owned")));
            assertFalse(source.exists(PackPath.of("/escape.txt")));
        }
    }

    @Test
    @DisplayName("a highly compressible ZIP entry is refused rather than expanded")
    void decompressionBombIsRefused(@TempDir Path dir) throws IOException {
        Path archive = dir.resolve("bomb.zip");
        byte[] zeros = new byte[8 * 1024 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry entry = new ZipEntry("shaders/bomb.fsh");
            zip.putNextEntry(entry);
            zip.write(zeros);
            zip.closeEntry();
        }
        // 8 MiB of zeros compresses far past the 200:1 ratio limit.
        IOException failure = assertThrows(IOException.class,
            () -> new ZipPackSource(archive, "bomb.zip").close());
        assertTrue(failure.getMessage().contains("compression ratio")
                || failure.getMessage().contains("bytes"),
            () -> "expected a limit message, got: " + failure.getMessage());
    }

    @Test
    @DisplayName("a symlink inside a directory pack cannot read outside it")
    void directorySymlinkEscapeIsRefused(@TempDir Path dir) throws IOException {
        Path outside = dir.resolve("outside.txt");
        Files.writeString(outside, "secret");
        Path pack = dir.resolve("pack");
        Files.createDirectories(pack.resolve("shaders"));

        Path link = pack.resolve("shaders").resolve("leak.glsl");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            // Symlinks are unavailable on this filesystem; the check below is meaningless.
            return;
        }

        try (DirectoryPackSource source = new DirectoryPackSource(pack, "pack")) {
            assertFalse(source.exists(PackPath.of("/shaders/leak.glsl")),
                "a symlink pointing outside the pack must not be readable");
            assertTrue(source.read(PackPath.of("/shaders/leak.glsl")).isEmpty());
        }
    }

    @Test
    @DisplayName("the pack loader recognises a wrapper directory but refuses ambiguity")
    void packLayoutResolution() throws IOException {
        MemoryPackSource direct = new MemoryPackSource("direct")
            .put("/shaders/composite.fsh", "// x");
        assertInstanceOf(PackLoader.Result.Ok.class, PackLoader.resolve(direct));
        assertEquals(PackPath.of("/shaders"),
            ((PackLoader.Result.Ok) PackLoader.resolve(direct)).shadersRoot());

        MemoryPackSource wrapped = new MemoryPackSource("wrapped")
            .put("/MyPack_v1/shaders/composite.fsh", "// x");
        assertEquals(PackPath.of("/MyPack_v1/shaders"),
            ((PackLoader.Result.Ok) PackLoader.resolve(wrapped)).shadersRoot());

        MemoryPackSource ambiguous = new MemoryPackSource("ambiguous")
            .put("/PackA/shaders/composite.fsh", "// x")
            .put("/PackB/shaders/composite.fsh", "// x");
        PackLoader.Result result = PackLoader.resolve(ambiguous);
        assertInstanceOf(PackLoader.Result.Invalid.class, result);
        assertTrue(((PackLoader.Result.Invalid) result).reason().contains("2 directories"));

        MemoryPackSource flattened = new MemoryPackSource("flattened")
            .put("/shaders.properties", "")
            .put("/composite.fsh", "// x");
        assertInstanceOf(PackLoader.Result.Invalid.class, PackLoader.resolve(flattened));
    }

    @Test
    @DisplayName("content hashes change with content and not with listing order")
    void contentHashIsStable() throws IOException {
        MemoryPackSource a = new MemoryPackSource("p")
            .put("/shaders/a.fsh", "one")
            .put("/shaders/b.fsh", "two");
        MemoryPackSource b = new MemoryPackSource("p")
            .put("/shaders/b.fsh", "two")
            .put("/shaders/a.fsh", "one");
        assertEquals(a.contentHash(), b.contentHash(),
            "insertion order must not change the hash");

        MemoryPackSource c = new MemoryPackSource("p")
            .put("/shaders/a.fsh", "one")
            .put("/shaders/b.fsh", "TWO");
        assertNotEquals(a.contentHash(), c.contentHash(),
            "a content change must change the hash");
    }

    private static void write(ZipOutputStream zip, String name, String content)
        throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
