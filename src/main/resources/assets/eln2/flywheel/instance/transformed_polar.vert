void flw_instanceVertex(in FlwInstance i) {
    flw_vertexPos = i.pose * flw_vertexPos;
    flw_vertexNormal = mat3(transpose(inverse(i.pose))) * flw_vertexNormal;

    // The passed color encodes an RGB tint, and a block light value.
    // We apply it to all the vertices at one pole of the model, and it will be interpolated across the pipe.
    vec4 poleData[2];
    poleData[0] = i.color1;
    poleData[1] = i.color2;

    int vertexIdQuad = gl_VertexID % 4;
    int pole = vertexIdQuad / 2;

    vec4 packedData = poleData[pole];

    flw_vertexColor = vec4(packedData.r, packedData.g, packedData.b, 1.0) * i.color;
    flw_vertexOverlay = i.overlay;

    // Some drivers have a bug where uint over float division is invalid, so use an explicit cast.
    flw_vertexLight = max(vec2(max(i.light[0] / 256.0, packedData.a), i.light[1] / 256.0), flw_vertexLight);
}
