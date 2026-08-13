package dev.retina.core;

import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ToolchainSmokeTest {
    @Test
    void shadercCompilesTrivialFragmentShader() {
        long compiler = Shaderc.shaderc_compiler_initialize();
        assertNotEquals(0L, compiler, "shaderc compiler must initialise");
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_3);
            String src = "#version 450\nlayout(location=0) out vec4 c;\nvoid main(){c=vec4(1.0);}\n";
            long result = Shaderc.shaderc_compile_into_spv(compiler, src,
                Shaderc.shaderc_fragment_shader, "smoke.frag", "main", options);
            assertNotEquals(0L, result);
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            assertEquals(Shaderc.shaderc_compilation_status_success, status,
                Shaderc.shaderc_result_get_error_message(result));
            ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
            assertNotNull(spv);
            assertTrue(spv.remaining() >= 20, "SPIR-V module should not be empty");
            assertEquals(0x07230203, spv.order(java.nio.ByteOrder.nativeOrder()).getInt(0),
                "SPIR-V magic number");
            Shaderc.shaderc_result_release(result);
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
