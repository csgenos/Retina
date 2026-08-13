#version 120
varying vec2 texCoord;
varying vec2 lightCoord;
varying vec4 tint;
void main() {
    gl_Position = ftransform();
    texCoord = gl_MultiTexCoord0.xy;
    lightCoord = gl_MultiTexCoord1.xy;
    tint = gl_Color;
}
