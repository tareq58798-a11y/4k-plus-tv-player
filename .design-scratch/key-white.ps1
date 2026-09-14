Add-Type -AssemblyName System.Drawing

$src = "C:\Users\ASUS\Downloads\4k-plus-tv-player\app\src\main\res\drawable-nodpi\logo_4k_plus_tv.jpg"
$dst = "C:\Users\ASUS\Downloads\4k-plus-tv-player\app\src\main\res\drawable-nodpi\brand_logo.png"

$cs = @"
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class WhiteKey {
    // The logo ships as artwork composited over solid white. Recover per-pixel alpha from how far
    // each pixel is from white, then un-composite the colour so edges stay clean on any background.
    public static void Run(string src, string dst) {
        using (Bitmap input = new Bitmap(src)) {
            int w = input.Width, h = input.Height;
            using (Bitmap output = new Bitmap(w, h, PixelFormat.Format32bppArgb)) {
                BitmapData inData = input.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                BitmapData outData = output.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
                int bytes = Math.Abs(inData.Stride) * h;
                byte[] buf = new byte[bytes];
                Marshal.Copy(inData.Scan0, buf, 0, bytes);

                for (int i = 0; i < bytes; i += 4) {
                    double b = buf[i], g = buf[i + 1], r = buf[i + 2];
                    double m = Math.Min(r, Math.Min(g, b));
                    double a = 1.0 - (m / 255.0);
                    if (a <= 0.004) {
                        buf[i] = 0; buf[i + 1] = 0; buf[i + 2] = 0; buf[i + 3] = 0;
                        continue;
                    }
                    double cr = (r - (1.0 - a) * 255.0) / a;
                    double cg = (g - (1.0 - a) * 255.0) / a;
                    double cb = (b - (1.0 - a) * 255.0) / a;
                    buf[i]     = (byte)Math.Max(0, Math.Min(255, cb));
                    buf[i + 1] = (byte)Math.Max(0, Math.Min(255, cg));
                    buf[i + 2] = (byte)Math.Max(0, Math.Min(255, cr));
                    buf[i + 3] = (byte)Math.Max(0, Math.Min(255, a * 255.0));
                }

                Marshal.Copy(buf, 0, outData.Scan0, bytes);
                input.UnlockBits(inData);
                output.UnlockBits(outData);
                output.Save(dst, ImageFormat.Png);
            }
        }
    }
}
"@

Add-Type -TypeDefinition $cs -ReferencedAssemblies System.Drawing
[WhiteKey]::Run($src, $dst)
Write-Output "wrote $dst"
