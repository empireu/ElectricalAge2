Generated as the difference between stone and iron ore with:

```csharp
using System.Numerics;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;

using var stone = Image.Load<Rgba32>("stone.png");
using var ironOre = Image.Load<Rgba32>("iron_ore.png");
using var result = new Image<Rgba32>(stone.Width, stone.Height);

var maxLuminance = 0.0f;
for (var y = 0; y < stone.Height; y++)
{
    for (var x = 0; x < stone.Width; x++)
    {
        var stonePx = stone[x, y];
        var ironPx = ironOre[x, y];

        if (Vector4.Distance(stonePx.ToVector4(), ironPx.ToVector4()) > 0.01f)
        {
            var luminance = 0.2126f * ironPx.R / 255f + 0.7152f * ironPx.G / 255f + 0.0722f * ironPx.B / 255f;

            if (luminance > maxLuminance)
            {
                maxLuminance = luminance;
            }
        }
    }
}

for (var y = 0; y < stone.Height; y++)
{
    for (var x = 0; x < stone.Width; x++)
    {
        var stonePx = stone[x, y];
        var ironPx = ironOre[x, y];
        
        if (Vector4.Distance(stonePx.ToVector4(), ironPx.ToVector4()) < 0.01f)
        {
             result[x, y] = new Rgba32(0, 0, 0, 0);
             continue;
        }

        var v = Math.Clamp((0.2126f * ironPx.R / 255f + 0.7152f * ironPx.G / 255f + 0.0722f * ironPx.B / 255f) / maxLuminance, 0f, 1f);
        result[x, y] = new Rgba32(v, v, v, 1.0f);
    }
}

result.SaveAsPng("ore_overlay.png");
```
