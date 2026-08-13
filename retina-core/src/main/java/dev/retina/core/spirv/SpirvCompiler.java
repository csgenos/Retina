/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.spirv;

import dev.retina.core.translate.ShaderStage;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;

/**
 * Compiles Vulkan GLSL to SPIR-V using shaderc, with validation enabled.
 *
 * <p>One compiler handle is expensive to create and is not thread-safe, so instances are
 * per-worker rather than global: {@link ShaderCompilePool} owns them. Compile options are
 * rebuilt per call because they carry the optimisation level, which differs between the
 * renderer profiles.
 *
 * <p>Validation is never disabled to make a shader compile. A pack that produces invalid
 * SPIR-V must be reported, not shipped to the driver — invalid SPIR-V is undefined behaviour
 * and the observable symptom is typically a device loss several frames later, at which point
 * the cause is unrecoverable from the crash.
 */
public final class SpirvCompiler implements AutoCloseable {

    /** How hard shaderc should optimise. */
    public enum Optimisation {
        /** No optimisation; fastest compile, best debuggability. */
        NONE(Shaderc.shaderc_optimization_level_zero),
        /** Optimise for size, which is what reduces driver compile time most. */
        SIZE(Shaderc.shaderc_optimization_level_size),
        /** Optimise for run-time performance. */
        PERFORMANCE(Shaderc.shaderc_optimization_level_performance);

        private final int shadercLevel;

        Optimisation(int shadercLevel) {
            this.shadercLevel = shadercLevel;
        }

        int shadercLevel() {
            return shadercLevel;
        }
    }

    private final long compiler;
    private boolean closed;

    public SpirvCompiler() {
        this.compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == MemoryUtil.NULL) {
            throw new IllegalStateException("shaderc_compiler_initialize returned NULL;"
                + " the shaderc native library failed to load");
        }
    }

    /**
     * The outcome of one compile.
     *
     * @param spirv the module, or empty on failure; a direct buffer the caller must free with
     *              {@link #free(Result)}
     * @param diagnostics the raw compiler output, empty when there was none
     * @param warningCount how many warnings shaderc reported
     */
    public record Result(Optional<ByteBuffer> spirv, String diagnostics, int warningCount,
                         long nativeHandle) {
        /** Whether a module was produced. */
        public boolean ok() {
            return spirv.isPresent();
        }

        /** The module size in bytes, or 0. */
        public int sizeBytes() {
            return spirv.map(ByteBuffer::remaining).orElse(0);
        }
    }

    /**
     * Compiles {@code source}.
     *
     * @param sourceName the name shaderc puts in diagnostics; use the pack's program name so
     *                   messages read sensibly even before Retina remaps them
     * @param macros macro definitions applied by shaderc itself, as {@code name=value} pairs
     */
    public Result compile(String source, ShaderStage stage, String sourceName,
                          Optimisation optimisation, List<String> macros) {
        checkOpen();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == MemoryUtil.NULL) {
            throw new IllegalStateException("shaderc_compile_options_initialize returned NULL");
        }
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_3);
            Shaderc.shaderc_compile_options_set_target_spirv(options,
                Shaderc.shaderc_spirv_version_1_6);
            Shaderc.shaderc_compile_options_set_optimization_level(options,
                optimisation.shadercLevel());
            // No include resolver is installed. Retina expands `#include` itself against the
            // pack, and shaderc's resolver would read from the host filesystem, so any
            // directive that survived translation must fail to compile rather than escape the
            // pack. The version is likewise left to the source: the translator has already
            // rewritten it, and forcing one here would mask a translation bug.
            for (String macro : macros) {
                int equals = macro.indexOf('=');
                if (equals < 0) {
                    Shaderc.shaderc_compile_options_add_macro_definition(options, macro, null);
                } else {
                    Shaderc.shaderc_compile_options_add_macro_definition(options,
                        macro.substring(0, equals), macro.substring(equals + 1));
                }
            }

            long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                stage.shadercKind(), sourceName, "main", options);
            if (result == MemoryUtil.NULL) {
                return new Result(Optional.empty(),
                    "shaderc returned no result object (out of memory?)", 0, MemoryUtil.NULL);
            }
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            String diagnostics = Shaderc.shaderc_result_get_error_message(result);
            int warnings = (int) Shaderc.shaderc_result_get_num_warnings(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                Shaderc.shaderc_result_release(result);
                return new Result(Optional.empty(),
                    describeStatus(status) + (diagnostics == null ? "" : "\n" + diagnostics),
                    warnings, MemoryUtil.NULL);
            }
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            if (bytes == null || bytes.remaining() < 20) {
                Shaderc.shaderc_result_release(result);
                return new Result(Optional.empty(),
                    "shaderc reported success but produced no module", warnings,
                    MemoryUtil.NULL);
            }
            return new Result(Optional.of(bytes.order(ByteOrder.nativeOrder())),
                diagnostics == null ? "" : diagnostics, warnings, result);
        } finally {
            Shaderc.shaderc_compile_options_release(options);
        }
    }

    /** Releases the native result backing {@code result}. */
    public static void free(Result result) {
        if (result.nativeHandle() != MemoryUtil.NULL) {
            Shaderc.shaderc_result_release(result.nativeHandle());
        }
    }

    private static String describeStatus(int status) {
        if (status == Shaderc.shaderc_compilation_status_invalid_stage) {
            return "shaderc could not infer the shader stage";
        }
        if (status == Shaderc.shaderc_compilation_status_compilation_error) {
            return "GLSL compilation failed";
        }
        if (status == Shaderc.shaderc_compilation_status_internal_error) {
            return "shaderc internal error";
        }
        if (status == Shaderc.shaderc_compilation_status_null_result_object) {
            return "shaderc produced a null result";
        }
        if (status == Shaderc.shaderc_compilation_status_invalid_assembly) {
            return "invalid SPIR-V assembly";
        }
        if (status == Shaderc.shaderc_compilation_status_validation_error) {
            return "SPIR-V validation failed";
        }
        if (status == Shaderc.shaderc_compilation_status_transformation_error) {
            return "SPIR-V transformation failed";
        }
        if (status == Shaderc.shaderc_compilation_status_configuration_error) {
            return "shaderc configuration error";
        }
        return "shaderc status " + status;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("SpirvCompiler has been closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            Shaderc.shaderc_compiler_release(compiler);
            closed = true;
        }
    }

    /** The SPIR-V magic number, used to sanity-check cached modules. */
    public static final int SPIRV_MAGIC = 0x07230203;

    /** Whether {@code module} starts with a plausible SPIR-V header. */
    public static boolean looksLikeSpirv(ByteBuffer module) {
        if (module == null || module.remaining() < 20 || module.remaining() % 4 != 0) {
            return false;
        }
        int magic = module.duplicate().order(ByteOrder.nativeOrder()).getInt(module.position());
        return magic == SPIRV_MAGIC;
    }
}
