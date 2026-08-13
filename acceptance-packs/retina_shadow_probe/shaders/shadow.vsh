#version 120
varying vec2 texCoord;
varying vec4 tint;
void main() {
    gl_Position = ftransform();
    texCoord = gl_MultiTexCoord0.xy;
    tint = gl_Color;
}
