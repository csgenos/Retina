#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;
varying vec2 texCoord;
varying vec2 lightCoord;
varying vec4 tint;
void main() {
    vec4 color = texture2D(texture, texCoord) * tint;
    color.rgb *= max(texture2D(lightmap, lightCoord).rgb, vec3(0.035));
    gl_FragColor = color;
}
