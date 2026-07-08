#version 150

/**
 * Shadow depth vertex shader. Transforms cube vertices by the light's view-projection matrix.
 * Uses a custom uniform name (u_lightViewProj) instead of ModelViewMat/ProjMat, because BufferUploader._drawWithShader
 * overwrites the built-in ModelViewMat and ProjMat uniforms with RenderSystem's values.
 */

in vec3 Position;

uniform mat4 u_lightViewProj;

void main() {
    gl_Position = u_lightViewProj * vec4(Position, 1.0);
}
