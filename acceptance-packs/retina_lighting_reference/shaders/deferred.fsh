#version 120
/* RENDERTARGETS: 0 */
uniform sampler2D colortex0;
varying vec2 texCoord;
void main() {
    // The Gate 4 reference pass is deliberately visually neutral; it proves that the
    // world scene can ping-pong through deferred before the lighting final pass.
    gl_FragColor = texture2D(colortex0, texCoord);
}
