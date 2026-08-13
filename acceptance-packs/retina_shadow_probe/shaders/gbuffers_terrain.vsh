#version 120
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
varying vec2 texCoord;
varying vec4 tint;
varying vec4 shadowClip;
void main() {
    gl_Position = ftransform();
    texCoord = gl_MultiTexCoord0.xy;
    tint = gl_Color;
    shadowClip = shadowProjection * shadowModelView * gl_Vertex;
}
