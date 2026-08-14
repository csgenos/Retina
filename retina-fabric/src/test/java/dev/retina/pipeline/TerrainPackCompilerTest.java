/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.pipeline;

import dev.retina.config.RetinaConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainPackCompilerTest {
    @TempDir
    Path temporary;

    @Test
    void translatesAdaptsAndValidatesTerrainFallbacks() throws Exception {
        Path shaderpacks = temporary.resolve("shaderpacks");
        Path shaders = shaderpacks.resolve("minimal").resolve("shaders");
        Files.createDirectories(shaders);
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
            #version 120
            varying vec2 texCoord;
            varying vec4 tint;
            void main() {
                gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                texCoord = gl_MultiTexCoord0.xy;
                tint = gl_Color * max(dot(gl_Normal, vec3(0.0, 1.0, 0.0)), 0.2);
            }
            """);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
            #version 120
            uniform sampler2D texture;
            varying vec2 texCoord;
            varying vec4 tint;
            void main() {
                gl_FragColor = texture2D(texture, texCoord) * tint;
            }
            """);

        PackManager manager = new PackManager(shaderpacks, temporary.resolve("config"),
            temporary.resolve("cache"));
        manager.ensureDirectories();
        RetinaConfig config = RetinaConfig.defaults().withSelectedPack("minimal");
        PreparedTerrainPack result = new TerrainPackCompiler().compile(manager, config);

        assertEquals(3, result.programs().size());
        assertTrue(result.uniforms().size() > 0);
        assertTrue(result.usesOffscreenTargets());
        assertTrue(result.finalProgram().internal());
        for (PreparedTerrainPack.Program program : result.programs().values()) {
            assertTrue(program.vertexSource().contains("retina_init_sodium_vertex();"));
            assertTrue(program.vertexSource().contains("a_RetinaNormal"));
            assertTrue(program.vertexSource().contains("RetinaSodiumDrawConstants"),
                "the terrain adapter must preserve Sodium's exact 20-byte push-constant ABI");
            assertTrue(program.vertexSource().contains("u_RegionID"));
            assertTrue(!program.vertexSource().contains("entityColor"),
                "Retina's generic push constants must not overlap Sodium terrain draws");
            assertTrue(program.fragmentSource().contains("u_BlockTex"));
        }
    }

    @Test
    void compilesTerrainMrtCompositeAndFinalAsOneTransaction() throws Exception {
        Path shaderpacks = temporary.resolve("shaderpacks-mrt");
        Path shaders = shaderpacks.resolve("mrt").resolve("shaders");
        Files.createDirectories(shaders);
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
            #version 120
            varying vec2 texCoord;
            varying vec2 lightCoord;
            varying float fogDistance;
            void main() {
                gl_Position = ftransform();
                texCoord = gl_MultiTexCoord0.xy;
                lightCoord = gl_MultiTexCoord1.xy;
                fogDistance = length((gl_ModelViewMatrix * gl_Vertex).xyz);
            }
            """);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
            #version 120
            /* RENDERTARGETS: 0,1 */
            /* const int colortex1Format = RGBA16F; */
            uniform sampler2D texture;
            uniform sampler2D lightmap;
            uniform vec3 fogColor;
            uniform float fogStart;
            uniform float fogEnd;
            uniform float sunAngle;
            varying vec2 texCoord;
            varying vec2 lightCoord;
            varying float fogDistance;
            void main() {
                vec4 color = texture2D(texture, texCoord)
                    * max(texture2D(lightmap, lightCoord), vec4(0.035));
                color.rgb = mix(color.rgb, fogColor,
                    smoothstep(fogStart, fogEnd, fogDistance));
                color.rgb *= 0.85 + 0.15 * max(cos(sunAngle * 6.2831853), 0.0);
                gl_FragData[0] = color;
                gl_FragData[1] = vec4(texCoord, 0.0, 1.0);
            }
            """);
        Files.writeString(shaders.resolve("composite.vsh"), """
            #version 120
            varying vec2 texCoord;
            void main() {
                gl_Position = ftransform();
                texCoord = gl_MultiTexCoord0.xy;
            }
            """);
        Files.writeString(shaders.resolve("composite.fsh"), """
            #version 120
            /* RENDERTARGETS: 0 */
            const bool colortex0MipmapEnabled = true;
            uniform sampler2D colortex0;
            uniform sampler2D colortex1;
            varying vec2 texCoord;
            void main() {
                gl_FragColor = mix(texture2D(colortex0, texCoord),
                    texture2D(colortex1, texCoord), 0.25);
            }
            """);
        Files.writeString(shaders.resolve("final.vsh"), """
            #version 120
            varying vec2 texCoord;
            void main() {
                gl_Position = ftransform();
                texCoord = gl_MultiTexCoord0.xy;
            }
            """);
        Files.writeString(shaders.resolve("final.fsh"), """
            #version 120
            uniform sampler2D colortex0;
            uniform sampler2D shadowtex1;
            uniform sampler2DShadow shadowtex0;
            varying vec2 texCoord;
            void main() {
                gl_FragColor = texture2D(colortex0, texCoord)
                    * (0.25 + 0.25 * texture2D(shadowtex1, texCoord).r
                        + 0.5 * shadow2D(shadowtex0, vec3(texCoord, 0.5)).r);
            }
            """);
        Files.writeString(shaders.resolve("shadow.vsh"), """
            #version 120
            varying vec2 texCoord;
            void main() {
                gl_Position = ftransform();
                texCoord = gl_MultiTexCoord0.xy;
            }
            """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
            #version 120
            const int shadowMapResolution = 512;
            const float shadowDistance = 96.0;
            uniform sampler2D texture;
            varying vec2 texCoord;
            void main() {
                gl_FragColor = texture2D(texture, texCoord);
            }
            """);
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
            #version 120
            varying vec2 texCoord;
            varying vec4 tint;
            void main() {
                gl_Position = ftransform();
                texCoord = gl_MultiTexCoord0.xy;
                tint = gl_Color;
            }
            """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
            #version 120
            uniform sampler2D texture;
            varying vec2 texCoord;
            varying vec4 tint;
            void main() {
                gl_FragColor = texture2D(texture, texCoord) * tint;
            }
            """);
        Files.writeString(shaders.resolve("gbuffers_particles.vsh"), """
            #version 120
            varying vec2 texCoord;
            varying vec4 tint;
            void main() {
                gl_Position = ftransform();
                texCoord = gl_MultiTexCoord0.xy;
                tint = gl_Color;
            }
            """);
        Files.writeString(shaders.resolve("gbuffers_particles.fsh"), """
            #version 120
            uniform sampler2D texture;
            varying vec2 texCoord;
            varying vec4 tint;
            void main() {
                gl_FragColor = texture2D(texture, texCoord) * tint;
            }
            """);

        PackManager manager = new PackManager(shaderpacks, temporary.resolve("config-mrt"),
            temporary.resolve("cache-mrt"));
        manager.ensureDirectories();
        PreparedTerrainPack result = new TerrainPackCompiler().compile(manager,
            RetinaConfig.defaults().withSelectedPack("mrt"));

        assertTrue(result.usesOffscreenTargets());
        assertEquals(java.util.List.of(0, 1),
            result.programs().get(PreparedTerrainPack.PassKind.SOLID).drawTargets());
        assertEquals(1, result.compositePrograms().size());
        assertEquals(java.util.List.of("colortex0", "colortex1"),
            result.compositePrograms().getFirst().samplers());
        assertEquals(dev.retina.core.target.TargetFormat.RGBA16F,
            result.targets().get(1).settings().format());
        assertTrue(result.targets().get(0).settings().mipmap());
        assertTrue(result.programs().get(PreparedTerrainPack.PassKind.SOLID).fragmentSource()
            .contains("u_LightTex"));
        assertTrue(result.programs().get(PreparedTerrainPack.PassKind.SOLID).fragmentSource()
            .contains("fogColor"));
        assertEquals(512, result.shadowProgram().resolution());
        assertEquals(96.0f, result.shadowProgram().distance());
        assertEquals("gbuffers_entities", result.entityProgram().sourceName());
        assertTrue(result.entityProgram().vertexSource().contains("retina_init_entity_vertex();"));
        assertTrue(result.entityProgram().fragmentSource().contains("Sampler0"));
        assertEquals("gbuffers_particles", result.particleProgram().sourceName());
        assertTrue(result.particleProgram().vertexSource()
            .contains("retina_init_particle_vertex();"));
        assertTrue(result.particleProgram().fragmentSource().contains("Sampler0"));
        assertEquals("sampler2D", result.finalProgram().samplerTypes().get("shadowtex1"));
        assertEquals("sampler2DShadow", result.finalProgram().samplerTypes().get("shadowtex0"));
        assertTrue(result.finalProgram().vertexSource()
            .contains("retina_init_fullscreen_vertex();"));
    }

    @Test
    void rejectsMoreAttachmentsThanBlaze3dCanBind() throws Exception {
        Path shaderpacks = temporary.resolve("shaderpacks-too-many");
        Path shaders = shaderpacks.resolve("too-many").resolve("shaders");
        Files.createDirectories(shaders);
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
            #version 120
            void main() { gl_Position = ftransform(); }
            """);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
            #version 120
            /* RENDERTARGETS: 0,1,2,3,4,5,6,7,8 */
            void main() { gl_FragData[0] = vec4(1.0); }
            """);

        PackManager manager = new PackManager(shaderpacks,
            temporary.resolve("config-too-many"), temporary.resolve("cache-too-many"));
        manager.ensureDirectories();
        TerrainPackCompiler.CompilationException failure = assertThrows(
            TerrainPackCompiler.CompilationException.class,
            () -> new TerrainPackCompiler().compile(manager,
                RetinaConfig.defaults().withSelectedPack("too-many")));

        assertTrue(failure.getMessage().contains("8"), failure::getMessage);
    }
}
