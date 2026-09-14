Add-Type -AssemblyName System.Drawing

$src = "C:\Users\ASUS\Downloads\4k-plus-tv-player\app\src\main\res\drawable-nodpi\brand_logo.png"
$dst = "C:\Users\ASUS\Downloads\4k-plus-tv-player\app\src\main\res\drawable-nodpi\brand_logo_dark.png"

$cs = @"
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class DarkVariant {
    // The logo's "4K"/"PLUS" ink is near-black, which disappears on a dark ground. Lift only the
    // darkest pixels toward a soft white, blending smoothly by brightness so the blue gradient in
    // "4K" and the orange play mark and "TV" are left exactly as they are (no hard seam).
    public static void Run(string src, string dst) {
        const double Cut = 110.0;
        const double TR = 232, TG = 240, TB = 250;

        using (Bitmap input = new Bitmap(src)) {
            int w = input.Width, h = input.Height;
            using (Bitmap output = new Bitmap(w, h, PixelFormat.Format32bppArgb)) {
                BitmapData inData = input.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                BitmapData outData = output.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
                int bytes = Math.Abs(inData.Stride) * h;
                byte[] buf = new byte[bytes];
                Marshal.Copy(inData.Scan0, buf, 0, bytes);

                for (int i = 0; i < bytes; i += 4) {
                    if (buf[i + 3] == 0) continue;
                    double b = buf[i], g = buf[i + 1], r = buf[i + 2];
                    double v = Math.Max(r, Math.Max(g, b));
                    if (v >= Cut) continue;
                    double t = (Cut - v) / Cut;
                    buf[i]     = (byte)Math.Round(b + (TB - b) * t);
                    buf[i + 1] = (byte)Math.Round(g + (TG - g) * t);
                    buf[i + 2] = (byte)Math.Round(r + (TR - r) * t);
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
[DarkVariant]::Run($src, $dst)
Write-Output "wrote $dst"
