package app.waveei.android.ui

/**
 * The tank's water shader, in AGSL, with the surface given from outside. Side view of the title card: the back face of the surface seen a little from
 * above, the water body with caustics, light shafts and the bubbles. Sizes are dp over the canvas's bottom left.
 * AGSL indexes a uniform array only by a loop's counter, so the edges, read between their points, come as a
 * 33 × 1 bitmap; the bubbles, read in a loop, as a uniform array.
 */
internal const val TANK_SHADER = """
uniform float2 R;        // the canvas, pixels
uniform float D;         // pixels to a dp
uniform float T;         // 4 + seconds × 0.15: the top face's shimmer, the shafts' drift
uniform float uLevel;    // the water's rest level, a share of the canvas's height
uniform float uStir;     // how much the water moves, 0 calm to 1
uniform float uCT;       // the caustics' own clock, faster as it moves
uniform float4 uBubs[48];  // each bubble: where it is (dp), its radius (dp), how strongly it shows, 0 to 1
uniform int uNB;         // how many bubbles there are
uniform shader uEdges;   // 33 × 1, one texel a point across the card: r the near edge, g the far edge, dp off the rest
uniform float3 cS;       // just under the surface
uniform float3 cD;       // at the bottom
uniform float3 cTop;     // the back face of the surface
uniform float3 cFar;     // what the back face fades to at its far edge: white by day, the card at night
uniform float uLine;     // the light line along the far edge: by day only
uniform float uCaus;     // the caustics' strength

const float RAD = 28.0;
const float RAYS = 0.55;
const float TOP = 11.0;  // the top face's depth, dp

float sdBox(float2 p, float2 b, float r) {
  float2 q = abs(p) - b + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
// the card: only its bottom corners are rounded, the top runs out of the canvas
float sdV(float2 p, float2 S) {
  return sdBox(p - float2(S.x * 0.5, S.y * 0.5 + 200.0), float2(S.x * 0.5, S.y * 0.5 + 200.0), RAD);
}
float caustic(float2 uv, float t) {
  float2 p = mod(uv * 6.28318, 6.28318) - 250.0;
  float2 i = p;
  float c = 1.0;
  float inten = 0.005;
  for (int n = 0; n < 5; n++) {
    float t2 = t * (1.0 - (3.5 / float(n + 1)));
    i = p + float2(cos(t2 - i.x) + sin(t2 + i.y), sin(t2 - i.y) + cos(t2 + i.x));
    c += 1.0 / length(float2(p.x / (sin(i.x + t2) / inten), p.y / (cos(i.y + t2) / inten)));
  }
  c /= 5.0;
  c = 1.17 - pow(c, 1.4);
  return clamp(pow(abs(c), 8.0), 0.0, 1.0);
}
float bubbles(float2 p) {
  float b = 0.0;
  for (int i = 0; i < 48; i++) {
    if (i >= uNB) break;
    float4 q = uBubs[i];
    float d = length(p - q.xy);
    float r = q.z;
    if (d > r + 1.0) continue;
    float disc = smoothstep(r + 0.7, r, d);
    float rim = smoothstep(r - 1.1, r - 0.2, d);
    b += disc * (0.35 + 0.65 * rim) * q.w;
  }
  return b;
}
float2 edgeAt(float k) { return float2(uEdges.eval(float2(k + 0.5, 0.5)).rg); }
float2 cr(float2 p0, float2 p1, float2 p2, float2 p3, float t) {
  return p1 + 0.5 * t * (p2 - p0 + t * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3 + t * (3.0 * (p1 - p2) + p3 - p0)));
}
// The near and the far edge at x, dp off the rest: a Catmull-Rom curve between the 33 simulated points, nothing else.
float2 edges(float x, float2 S) {
  float f = clamp(x / S.x, 0.0, 1.0) * 32.0;
  float i = min(floor(f), 31.0);
  return cr(edgeAt(max(i - 1.0, 0.0)), edgeAt(i), edgeAt(i + 1.0), edgeAt(min(i + 2.0, 32.0)), f - i);
}
float front(float x, float2 S) { return uLevel * S.y + edges(x, S).x; }

half4 main(float2 fc) {
  float2 S = R / D;
  float2 p = float2(fc.x, R.y - fc.y) / D;
  float d = sdV(p, S);
  // no meniscus at the card's sides: the pool's walls are beyond them, so the water runs straight out of the card
  float2 here = edges(p.x, S);
  float surf = uLevel * S.y + here.x;
  float bk = uLevel * S.y + TOP + here.y;
  float3 col = float3(0.0);
  float a = 0.0;
  float inA = clamp(-d * D + 0.5, 0.0, 1.0);
  // back face of the surface, seen a little from above
  float tA = clamp((bk - p.y) * D + 0.5, 0.0, 1.0) * clamp((p.y - surf) * D + 0.5 + 1.0, 0.0, 1.0);
  if (tA > 0.0) {
    float t = clamp((p.y - surf) / max(bk - surf, 1.0), 0.0, 1.0);
    float3 tc = mix(cTop, mix(cTop, cFar, 0.38), t);
    float s = sin(p.x * 0.06 + T * 0.7 + sin(p.x * 0.021 - T * 0.45) * 3.0) * sin(p.x * 0.013 - T * 0.3);
    tc += 0.07 * s * (1.0 - t);
    // a thicker band is the face tilted toward us: we see into it, bluer; a thinner one is seen grazing: brighter
    float th = (bk - surf) / TOP - 1.0;
    tc = mix(tc, mix(cS, cTop, 0.45), clamp(th * 1.6, 0.0, 0.45));
    tc = mix(tc, float3(1.0), clamp(-th * 1.6, 0.0, 0.45));
    float backLine = exp(-abs(p.y - bk) * D * 0.9);
    tc = mix(tc, float3(1.0), backLine * uLine);
    float tAlpha = mix(0.96, 0.78, t);
    // the colour is not dimmed by the coverage: the alpha carries it, once
    col = tc;
    a = tAlpha * (tA * inA);
  }
  // water body
  float wA = clamp((surf - p.y) * D + 0.5, 0.0, 1.0) * inA;
  if (wA > 0.0) {
    float dpx = surf - p.y;
    float depth = clamp(dpx / max(surf, 1.0), 0.0, 1.0);
    float3 wc = mix(cS, cD, pow(depth, 0.8));
    // The light comes in through the surface above: where it slopes, it is bent sideways, the more the deeper it goes,
    // so the caustics run and squeeze under the waves. Calm water gives them weak and even; moving water gathers them
    // sharp and bright, and their own stirring speeds up (uCT).
    float slope = (front(p.x + 3.0, S) - front(p.x - 3.0, S)) / 6.0;
    float c = caustic(float2(p.x + slope * dpx * (0.25 + 0.5 * uStir), p.y * 1.25) / 150.0, uCT);
    c = mix(0.12 + 0.6 * c, c, uStir) * (0.7 + 0.6 * uStir);
    wc += uCaus * c * float3(0.72, 0.9, 1.0) * (0.22 + 0.78 * depth);
    wc += uCaus * 0.55 * c * exp(-p.y / 9.0);
    // light shafts, leaning with the surface over them
    float sh = (p.x + dpx * (0.42 + 0.5 * slope)) / S.x;
    float r = pow(max(0.0, 0.5 + 0.5 * sin(sh * 21.0 + T * 0.25) * sin(sh * 8.3 - T * 0.17 + 1.0)), 3.0);
    wc += RAYS * (0.6 + 0.6 * uStir) * r * exp(-depth * 2.2) * float3(0.55, 0.8, 1.0) * 0.32;
    // underside of the surface and its bright line
    wc = mix(wc, min(cS * 1.1 + 0.07, float3(1.0)), exp(-dpx / 6.0) * 0.55);
    wc = mix(wc, float3(1.0), exp(-dpx * D * 0.55) * 0.85);
    // the side walls absorb a little
    wc *= 1.0 - 0.10 * exp(-max(-d, 0.0) / 7.0);
    float b = bubbles(p);
    wc = mix(wc, float3(1.0), clamp(b, 0.0, 1.0) * 0.55);
    col = a > 0.0 ? mix(col, wc, wA) : wc;
    a = mix(a, 1.0, wA);
  }
  return half4(half3(col * a), half(a));
}
"""
