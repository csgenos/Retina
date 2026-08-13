#version 120
const int shadowMapResolution = 1024;
const float shadowDistance = 96.0;
uniform sampler2D texture;
varying vec2 texCoord;
varying vec4 tint;
void main() {
    gl_FragColor = texture2D(texture, texCoord) * tint;
}
