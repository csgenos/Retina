#version 120
varying vec2 texCoord;
varying vec2 lightCoord;
varying vec4 tint;
varying float fogDistance;
void main() {
    vec4 eyePosition = gl_ModelViewMatrix * gl_Vertex;
    gl_Position = gl_ProjectionMatrix * eyePosition;
    texCoord = gl_MultiTexCoord0.xy;
    lightCoord = gl_MultiTexCoord1.xy;
    tint = gl_Color;
    fogDistance = length(eyePosition.xyz);
}
