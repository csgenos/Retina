#version 120
/* RENDERTARGETS: 0,1 */
uniform sampler2D texture;
varying vec2 texCoord;
varying vec4 tint;
varying vec4 shadowClip;
void main() {
    gl_FragData[0] = texture2D(texture, texCoord) * tint;
    gl_FragData[1] = shadowClip;
}
