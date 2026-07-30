#include "flywheel:util/matrix.glsl"

// Indirect Hi-Z falsely culls thin face-mounted cable/conduit instances: polar
// tube meshes omit endcaps, so model spheres are tiny and sit against block depth.
// Inflate after posing so frustum culling still works but occlusion stops eating them.
void flw_transformBoundingSphere(in FlwInstance i, inout vec3 center, inout float radius) {
    transformBoundingSphere(i.pose, center, radius);
    radius = max(radius * 8.0, 1.5);
}
