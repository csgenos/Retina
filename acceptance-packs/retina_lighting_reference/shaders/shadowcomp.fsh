#version 120
/* RENDERTARGETS: 0 */
uniform sampler2D colortex0;
uniform sampler2D shadowtex1;
varying vec2 texCoord;
void main() {
    // Bind the completed raw shadow map without changing the diagnostic reference appearance.
    gl_FragColor = texture2D(colortex0, texCoord)
        + vec4(texture2D(shadowtex1, texCoord).r * 0.0);
}
