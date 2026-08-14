#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
varying vec2 texCoord;
varying vec2 lightCoord;
varying vec4 tint;
varying float fogDistance;
void main() {
    vec4 color = texture2D(texture, texCoord) * tint;
    color.rgb *= max(texture2D(lightmap, lightCoord).rgb, vec3(0.035));
    color.rgb = mix(color.rgb, fogColor, smoothstep(fogStart, fogEnd, fogDistance) * 0.88);
    gl_FragColor = color;
}
