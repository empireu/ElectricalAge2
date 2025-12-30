Generated from iron ore with:

```csharp
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;

using var sourceImage = Image.Load<Rgba32>("raw_iron.png");
using var result = new Image<Rgba32>(sourceImage.Width, sourceImage.Height);

var maxLuminance = 0.0f;
for (var y = 0; y < sourceImage.Height; y++)
{
    for (var x = 0; x < sourceImage.Width; x++)
    {
        var px = sourceImage[x, y];
        
        if (px.A == 0)
        {
            continue;
        }

        var luminance = 0.2126f * px.R / 255f + 0.7152f * px.G / 255f + 0.0722f * px.B / 255f;

        if (luminance > maxLuminance)
        {
            maxLuminance = luminance;
        }
    }
}

for (var y = 0; y < sourceImage.Height; y++)
{
    for (var x = 0; x < sourceImage.Width; x++)
    {
        var px = sourceImage[x, y];
        
        if (px.A == 0) 
        {
            result[x, y] = new Rgba32(0, 0, 0, 0);
            continue;
        }
        
        var v = Math.Clamp((0.2126f * px.R / 255f + 0.7152f * px.G / 255f + 0.0722f * px.B / 255f) / maxLuminance, 0f, 1f);
        result[x, y] = new Rgba32(v, v, v, px.A);
    }
}

result.SaveAsPng("raw_ore_overlay.png");
```
