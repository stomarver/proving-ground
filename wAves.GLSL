
#define SPEED_MULT 0.80
#define GLOSS_SCALE 1.20
#define FLASH_SCALE 0.45
#define NORMAL_SMOOTH 0.12
#define SHADOW_SOFTNESS 0.15
#define HALO_STRENGTH 0.18
#define HIGHLIGHT_STRENGTH 0.7
#define MOUSE_FORCE 0.35
#define MOUSE_RADIUS 0.7

#define C_DEEP  vec3(0.01, 0.03, 0.12) // Чуть светлее, чтобы видеть детали в тенях
#define C_AQUA  vec3(0.0, 0.65, 0.95)
#define C_GLOW  vec3(0.65, 0.92, 1.0)

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.1, 78.233))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fractalBubble(vec2 p, float t) {
    vec2 q = vec2(0.0);
    q.x = noise(p + 0.00 * t);
    q.y = noise(p + vec2(1.0));
    vec2 r = vec2(0.0);
    r.x = noise(p + 1.0 * q + vec2(1.7, 9.2) + 0.15 * t);
    r.y = noise(p + 1.0 * q + vec2(8.3, 2.8) + 0.126 * t);
    return noise(p + 4.0 * r);
}

float fbm(vec2 p, float t) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(1.6, 1.2, -1.2, 1.6); 
    for (int i = 0; i < 6; i++) {
        v += a * fractalBubble(p, t);
        p = rot * p * 0.65;
        a *= 0.55;
    }
    return v;
}

void mainImage( out vec4 fragColor, in vec2 fragCoord )
{
    vec2 uv = (fragCoord - 0.5 * iResolution.xy) / iResolution.y;
    vec2 m = (iMouse.xy - 0.5 * iResolution.xy) / iResolution.y;
    float t = iTime * SPEED_MULT;

    float isPressed = clamp(iMouse.z, 0.0, 1.0);
    float dist = length(uv - m);
    float push = smoothstep(MOUSE_RADIUS, 0.0, dist) * isPressed;
    vec2 pushDir = normalize(uv - m + 0.0001);
    vec2 uvDistorted = uv + pushDir * push * MOUSE_FORCE;

    float backgroundNoise = fbm(uvDistorted * 1.5, t * 0.5) * 0.4;
    
    float d = fbm(uvDistorted * GLOSS_SCALE, t);
    d = mix(d, backgroundNoise, 0.2);
    
    float e = NORMAL_SMOOTH;
    float d_x = fbm((uvDistorted + vec2(e, 0.0)) * GLOSS_SCALE, t);
    float d_y = fbm((uvDistorted + vec2(0.0, e)) * GLOSS_SCALE, t);
    vec2 normal = vec2(d_x - d, d_y - d) / e;
    
    float shadow = clamp(dot(normal, vec2(0.5, 0.5)), 0.0, 1.0);
    float rim = 1.0 - clamp(length(normal * 0.2), 0.0, 1.0);
    float highlights = pow(rim, 18.0) * HIGHLIGHT_STRENGTH;
    
    float bubblePattern = pow(d, 2.0); 
    vec3 color = mix(C_DEEP, C_AQUA * 0.4, bubblePattern + backgroundNoise);
    color = mix(color, C_DEEP, shadow * SHADOW_SOFTNESS);
    
    color = mix(color, C_AQUA, pow(d, 3.5) * 0.9); 

    float flashNoise = fbm(uvDistorted * FLASH_SCALE + d * 0.3, t * 0.4);
    float pulse = sin(t * 1.2) * 0.5 + 0.5;
    float flash = pow(flashNoise, 7.0) * 0.8;
    float halo = pow(flashNoise, 3.0) * HALO_STRENGTH;
    color += C_GLOW * (flash + halo) * pulse;

    color += C_AQUA * push * 0.15;

    float spec = pow(d, 12.0);
    color += C_GLOW * spec * 0.5;
    
    vec3 coloredHighlight = mix(C_AQUA, C_GLOW, 0.4);
    color += coloredHighlight * highlights * (bubblePattern + 0.1);

    float glassEdge = smoothstep(0.3, 0.8, d);
    color = mix(color, C_GLOW, glassEdge * 0.08);
    
    color *= 1.15 - length(uv) * 0.3;
    color = mix(color, color * vec3(0.9, 0.95, 1.1), length(uv)); 

    color = smoothstep(0.0, 1.0, color);
    
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
