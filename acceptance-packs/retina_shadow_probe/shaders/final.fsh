#version 120
uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2DShadow shadowtex0;
varying vec2 texCoord;
void main() {
    vec4 color = texture2D(colortex0, texCoord);
    vec4 shadowClip = texture2D(colortex1, texCoord);
    vec3 shadowCoord = shadowClip.xyz / max(shadowClip.w, 0.0001) * 0.5 + 0.5;
    bool outside = any(lessThan(shadowCoord.xy, vec2(0.002)))
        || any(greaterThan(shadowCoord.xy, vec2(0.998)));
    float lit = outside ? 1.0 : shadow2D(shadowtex0, shadowCoord).r;
    color.rgb *= mix(0.42, 1.0, lit);
    gl_FragColor = color;
}
