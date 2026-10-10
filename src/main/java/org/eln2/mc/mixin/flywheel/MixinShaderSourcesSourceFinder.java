package org.eln2.mc.mixin.flywheel;

import dev.engine_room.flywheel.backend.glsl.LoadResult;
import dev.engine_room.flywheel.backend.glsl.SourceFile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Function;

/**
 * Flywheel's SourceFinder keeps the first shader for a given RL, so jarJar flywheel
 * wins over asset overrides. Replace the indirect cull shader with a frustum-only
 * variant: skip Hi-Z depth-pyramid occlusion, which falsely culls thin/open eln2 meshes
 * against terrain depth on flywheel:indirect.
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.glsl.ShaderSources$SourceFinder", remap = false)
public abstract class MixinShaderSourcesSourceFinder {
    @Unique
    private static final Logger ELN2$LOGGER = LogManager.getLogger("eln2");

    @Unique
    private static final String ELN2$CULL_PATH = "internal/indirect/cull.glsl";

    /**
     * Stock Flywheel cull.glsl with the depth-pyramid occlusion block removed.
     * Frustum test is kept so off-screen instances are still skipped.
     */
    @Unique
    private static final String ELN2$FRUSTUM_ONLY_CULL = """
        #include "flywheel:internal/indirect/buffer_bindings.glsl"
        #include "flywheel:internal/indirect/model_descriptor.glsl"
        #include "flywheel:internal/uniforms/uniforms.glsl"
        #include "flywheel:util/matrix.glsl"
        #include "flywheel:internal/indirect/matrices.glsl"

        layout(local_size_x = 32) in;

        layout(std430, binding = _FLW_DRAW_INSTANCE_INDEX_BUFFER_BINDING) restrict writeonly buffer TargetBuffer {
            uint _flw_instanceIndices[];
        };

        const uint _FLW_PAGE_COUNT_OFFSET = 26u;
        const uint _FLW_MODEL_INDEX_MASK = 0x3FFFFFF;

        layout(std430, binding = _FLW_PAGE_FRAME_DESCRIPTOR_BUFFER_BINDING) restrict readonly buffer PageFrameDescriptorBuffer {
            uint _flw_pageFrameDescriptors[];
        };

        layout(std430, binding = _FLW_MODEL_BUFFER_BINDING) restrict buffer ModelBuffer {
            ModelDescriptor _flw_models[];
        };

        layout(std430, binding = _FLW_MATRIX_BUFFER_BINDING) restrict readonly buffer MatrixBuffer {
            Matrices _flw_matrices[];
        };

        layout(binding = 0) uniform sampler2D _flw_depthPyramid;

        bool _flw_testSphere(vec3 center, float radius) {
            bvec4 xyInside = greaterThanEqual(fma(flw_frustumPlanes.xyX, center.xxxx, fma(flw_frustumPlanes.xyY, center.yyyy, fma(flw_frustumPlanes.xyZ, center.zzzz, flw_frustumPlanes.xyW))), -radius.xxxx);
            bvec2 zInside = greaterThanEqual(fma(flw_frustumPlanes.zX, center.xx, fma(flw_frustumPlanes.zY, center.yy, fma(flw_frustumPlanes.zZ, center.zz, flw_frustumPlanes.zW))), -radius.xx);

            return all(xyInside) && all(zInside);
        }

        bool _flw_isVisible(uint instanceIndex, uint modelIndex) {
            uint matrixIndex = _flw_models[modelIndex].matrixIndex;
            BoundingSphere sphere = _flw_models[modelIndex].boundingSphere;

            vec3 center;
            float radius;
            _flw_unpackBoundingSphere(sphere, center, radius);

            FlwInstance instance = _flw_unpackInstance(instanceIndex);

            flw_transformBoundingSphere(instance, center, radius);

            if (matrixIndex > 0) {
                transformBoundingSphere(_flw_matrices[matrixIndex].pose, center, radius);
            }

            return _flw_testSphere(center, radius);
        }

        void main() {
            uint pageIndex = gl_WorkGroupID.x << 1u;

            if (pageIndex >= _flw_pageFrameDescriptors.length()) {
                return;
            }

            uint modelIndex = _flw_pageFrameDescriptors[pageIndex];

            uint pageValidity = _flw_pageFrameDescriptors[pageIndex + 1];

            if (((1u << gl_LocalInvocationID.x) & pageValidity) == 0) {
                return;
            }

            uint instanceIndex = gl_GlobalInvocationID.x;

            if (_flw_isVisible(instanceIndex, modelIndex)) {
                uint localIndex = atomicAdd(_flw_models[modelIndex].instanceCount, 1);
                uint targetIndex = _flw_models[modelIndex].baseInstance + localIndex;
                _flw_instanceIndices[targetIndex] = instanceIndex;
            }
        }
        """;

    @Shadow
    @Final
    private Map<ResourceLocation, LoadResult> results;

    @Shadow
    public abstract LoadResult recursiveLoad(ResourceLocation loc);

    @Inject(method = "rootLoad", at = @At("TAIL"), remap = false)
    private void eln2$replaceIndirectCull(ResourceLocation fileLoc, Resource resource, CallbackInfo ci) {
        String path = fileLoc.getPath();
        if (!path.endsWith(ELN2$CULL_PATH)) {
            return;
        }

        String strippedPath = path.startsWith("flywheel/") ? path.substring("flywheel/".length()) : path;
        ResourceLocation stripped = ResourceLocation.fromNamespaceAndPath(fileLoc.getNamespace(), strippedPath);

        Function<ResourceLocation, LoadResult> lookup = this::recursiveLoad;
        results.put(stripped, SourceFile.parse(lookup, stripped, ELN2$FRUSTUM_ONLY_CULL));
        ELN2$LOGGER.info("Using frustum-only Flywheel indirect cull (Hi-Z occlusion disabled for eln2 meshes).");
    }
}
