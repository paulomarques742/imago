#version 100
precision highp float;
uniform sampler2D uVideo;
uniform sampler2D uMask;
uniform vec2 uScale;
uniform vec2 uOffset;
uniform vec2 uWindowOrigin;
uniform vec2 uWindowSize;
uniform vec2 uCentre;
uniform vec2 uFrameSize;
uniform vec2 uRotation;
uniform vec2 uMirror;
uniform float uVisible;
varying vec2 vPosition;

void main() {
    if (uVisible < 0.5) {
        gl_FragColor = vec4(0.0);
        return;
    }
    vec2 pixel = uWindowOrigin + vec2(vPosition.x, 1.0 - vPosition.y) * uWindowSize;
    vec2 delta = pixel - uCentre;
    vec2 local = vec2(delta.x * uRotation.x + delta.y * uRotation.y,
                      -delta.x * uRotation.y + delta.y * uRotation.x) * uMirror;
    vec2 frame = vec2(0.5 + local.x / uFrameSize.x, 0.5 - local.y / uFrameSize.y);
    vec4 source = texture2D(uVideo, frame * uScale + uOffset);
    // Bitmap rows start at the top; decoded GL video coordinates start at the bottom.
    float coverage = texture2D(uMask, vec2(vPosition.x, 1.0 - vPosition.y)).a;
    // Media3 effects use straight alpha: multiplying RGB here would darken the mask's edges twice.
    gl_FragColor = vec4(source.rgb, source.a * coverage);
}
