void flw_instanceVertex(in FlwInstance i) {
    flw_vertexPos = i.pose * flw_vertexPos;
    flw_vertexNormal = mat3(transpose(inverse(i.pose))) * flw_vertexNormal;
    flw_vertexColor *= i.color;
    flw_vertexOverlay = i.overlay;

    float lightOverride = i.lightOverride;

    flw_vertexLight = max(vec2(max(i.light[0] / 256.0, lightOverride), i.light[1] / 256.0), flw_vertexLight);
}
