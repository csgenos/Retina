#version 120
/* RENDERTARGETS: 0 */
uniform sampler2D colortex0;
varying vec2 texCoord;
void main() {
    // A neutral prepare pass confirms pre-terrain target ping-pong without changing the scene.
    gl_FragColor = texture2D(colortex0, texCoord);
}
