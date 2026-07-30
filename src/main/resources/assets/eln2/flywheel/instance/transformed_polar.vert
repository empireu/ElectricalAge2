void flw_instanceVertex(in FlwInstance i) {
    flw_vertexPos = i.pose * flw_vertexPos;
    flw_vertexNormal = mat3(transpose(inverse(i.pose))) * flw_vertexNormal;

    // Same base path as stock transformed.vert, then apply pole tip tint/light.
    flw_vertexColor *= i.color;
    flw_vertexOverlay = i.overlay;
    flw_vertexLight = max(vec2(i.light) / 256.0, flw_vertexLight);

    vec4 poleData[2];
    poleData[0] = i.color1;
    poleData[1] = i.color2;

    int vertexIdQuad = gl_VertexID % 4;
    int pole = vertexIdQuad / 2;
    vec4 packedData = poleData[pole];

    flw_vertexColor.rgb *= packedData.rgb;
    flw_vertexLight = max(vec2(max(flw_vertexLight.x, packedData.a), flw_vertexLight.y), flw_vertexLight);
}
