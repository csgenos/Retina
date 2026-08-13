#version 120
/* RENDERTARGETS: 0,1 */
uniform sampler2D texture;
uniform sampler2D lightmap;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float sunAngle;
varying vec2 texCoord;
varying vec2 lightCoord;
varying vec4 tint;
varying float fogDistance;
varying vec4 shadowClip;
void main() {
    vec4 color = texture2D(texture, texCoord) * tint;
    vec3 light = max(texture2D(lightmap, lightCoord).rgb, vec3(0.035));
    float solar = max(cos(sunAngle * 6.2831853), 0.0);
    vec3 daylightTint = mix(vec3(0.78, 0.70, 0.58), vec3(1.0), solar);
    color.rgb *= light * daylightTint;
    float fog = smoothstep(fogStart, fogEnd, fogDistance);
    color.rgb = mix(color.rgb, fogColor, fog * 0.88);
    gl_FragData[0] = color;
    gl_FragData[1] = shadowClip;
}
