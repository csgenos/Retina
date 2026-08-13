#version 120
uniform sampler2D texture;
varying vec2 texCoord;
varying vec4 tint;
void main() {
    vec4 color = texture2D(texture, texCoord) * tint;
    // Subtle probe: standard entity draws must take this program.
    color.rgb *= vec3(1.0, 0.92, 0.82);
    gl_FragColor = color;
}
