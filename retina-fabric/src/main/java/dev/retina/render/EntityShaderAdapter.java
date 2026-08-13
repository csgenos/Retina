/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import dev.retina.core.translate.GlslLexer;
import dev.retina.core.translate.TranslatedSource;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adapts legacy pack GLSL to Minecraft's stable {@code DefaultVertexFormat.ENTITY} ABI. */
public final class EntityShaderAdapter {
    private static final Pattern UNIFORMS = Pattern.compile(
        "layout\\s*\\(\\s*std140\\s*,[^)]*\\)\\s*uniform\\s+RetinaUniforms\\s*\\{"
            + ".*?\\}\\s*;", Pattern.DOTALL);
    private static final Pattern PUSH = Pattern.compile(
        "layout\\s*\\(\\s*push_constant\\s*\\)\\s*uniform\\s+RetinaDrawConstants\\s*\\{"
            + ".*?\\}\\s*retina_draw\\s*;", Pattern.DOTALL);
    private static final Pattern LEGACY_INPUT = Pattern.compile(
        "layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*in\\s+"
            + "(?:vec[234])\\s+retina_(?:Vertex|Color|Normal|MultiTexCoord[01345])\\s*;");
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");

    private static final String ABI = """
        layout(std140, binding = 0) uniform DynamicTransforms {
            mat4 ModelViewMat;
            vec4 ColorModulator;
            vec3 ModelOffset;
            mat4 TextureMat;
        };
        layout(std140, binding = 1) uniform Projection { mat4 ProjMat; };
        layout(std140, binding = 2) uniform Fog {
            vec4 FogColor;
            float FogEnvironmentalStart;
            float FogEnvironmentalEnd;
            float FogRenderDistanceStart;
            float FogRenderDistanceEnd;
            float FogSkyEnd;
            float FogCloudsEnd;
        };
        layout(std140, binding = 3) uniform Lighting { vec3 Light0_Direction; vec3 Light1_Direction; };
        """;

    private static final String VERTEX_INPUTS = """
        layout(location = 0) in vec3 Position;
        layout(location = 1) in vec4 Color;
        layout(location = 2) in vec2 UV0;
        layout(location = 3) in ivec2 UV1;
        layout(location = 4) in ivec2 UV2;
        layout(location = 5) in vec3 Normal;
        vec4 retina_Vertex;
        vec4 retina_Color;
        vec3 retina_Normal;
        vec4 retina_MultiTexCoord0;
        vec4 retina_MultiTexCoord1;
        vec4 retina_MultiTexCoord3;
        vec4 retina_MultiTexCoord4;
        vec4 retina_MultiTexCoord5;

        """;

    private EntityShaderAdapter() {
    }

    public static String adapt(TranslatedSource translated, boolean vertex) {
        String source = translated.text();
        if (!UNIFORMS.matcher(source).find()) {
            throw new IllegalArgumentException("translated entity shader has no RetinaUniforms block");
        }
        source = UNIFORMS.matcher(source).replaceFirst(Matcher.quoteReplacement(
            ABI + (vertex ? VERTEX_INPUTS : "")));
        source = PUSH.matcher(source).replaceFirst("");
        source = source.replaceAll("set\\s*=\\s*\\d+\\s*,\\s*", "")
            .replaceAll(",\\s*set\\s*=\\s*\\d+", "");
        source = rewriteResources(source);
        source = source.replace("retina_ModelViewProjectionMatrixInverse", "inverse(ProjMat * ModelViewMat)")
            .replace("retina_ModelViewProjectionMatrix", "ProjMat * ModelViewMat")
            .replace("retina_ModelViewMatrixInverse", "inverse(ModelViewMat)")
            .replace("retina_ModelViewMatrix", "ModelViewMat")
            .replace("retina_ProjectionMatrixInverse", "inverse(ProjMat)")
            .replace("retina_ProjectionMatrix", "ProjMat")
            .replace("retina_NormalMatrix", "mat3(ModelViewMat)");
        if (!vertex) {
            return source;
        }
        source = LEGACY_INPUT.matcher(source).replaceAll("");
        Matcher main = MAIN.matcher(source);
        if (!main.find()) {
            throw new IllegalArgumentException("entity vertex shader has no main() function");
        }
        String locals = """
            void retina_init_entity_vertex() {
                retina_Vertex = vec4(Position + ModelOffset, 1.0);
                retina_Color = Color;
                retina_Normal = normalize(Normal);
                retina_MultiTexCoord0 = vec4(UV0, 0.0, 1.0);
                retina_MultiTexCoord1 = vec4(vec2(UV2) / 256.0, 0.0, 1.0);
                retina_MultiTexCoord3 = vec4(vec2(UV1), 0.0, 1.0);
                retina_MultiTexCoord4 = vec4(0.0);
                retina_MultiTexCoord5 = vec4(0.0);
            }

            """;
        source = source.substring(0, main.start()) + locals + source.substring(main.start());
        main = MAIN.matcher(source);
        if (!main.find()) {
            throw new IllegalStateException("entity main() disappeared while injecting the ABI");
        }
        return source.substring(0, main.end()) + "\n    retina_init_entity_vertex();"
            + source.substring(main.end());
    }

    private static String rewriteResources(String source) {
        List<GlslLexer.Token> tokens = new java.util.ArrayList<>(GlslLexer.tokenize(source));
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER) {
                continue;
            }
            if (token.text().equals("gtexture")) {
                tokens.set(index, token.withText("Sampler0"));
            } else if (token.text().equals("lightmap")) {
                tokens.set(index, token.withText("Sampler2"));
            }
        }
        return GlslLexer.render(tokens);
    }
}
