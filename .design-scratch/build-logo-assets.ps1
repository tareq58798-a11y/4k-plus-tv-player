Add-Type -AssemblyName System.Drawing

$src  = "C:\Users\ASUS\Downloads\4k-plus-tv-player\app\src\main\res\drawable-nodpi\logo_4k_plus_tv.jpg"
$out  = "C:\Users\ASUS\Downloads\4k-plus-tv-player\app\src\main\res\drawable-nodpi"

$cs = @"
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class LogoAssets {
    const int Margin = 70;       // room for the bloom so it is not clipped
    const double InkCut = 110.0; // below this brightness the ink is lifted for dark backgrounds
    const double LiftR = 232, LiftG = 240, LiftB = 250;
    const double GlowR = 60, GlowG = 165, GlowB = 255;

    static byte[] Read(Bitmap bmp, out int w, out int h) {
        w = bmp.Width; h = bmp.Height;
        BitmapData d = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        byte[] buf = new byte[Math.Abs(d.Stride) * h];
        Marshal.Copy(d.Scan0, buf, 0, buf.Length);
        bmp.UnlockBits(d);
        return buf;
    }

    static void Write(string path, byte[] buf, int w, int h) {
        using (Bitmap bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb)) {
            BitmapData d = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
            Marshal.Copy(buf, 0, d.Scan0, buf.Length);
            bmp.UnlockBits(d);
            bmp.Save(path, ImageFormat.Png);
        }
    }

    // Recover alpha from distance-to-white and un-composite the colour, so the artwork that was
    // drawn over solid white can sit on any background without white fringing.
    static byte[] KeyWhite(byte[] s, int w, int h) {
        // The source is a JPEG, so its "white" is noisy: without a floor, every background pixel
        // keeps a sliver of alpha, which a bloom pass then amplifies into a solid slab.
        const double Floor = 0.09;
        byte[] o = new byte[s.Length];
        for (int i = 0; i < s.Length; i += 4) {
            double b = s[i], g = s[i + 1], r = s[i + 2];
            double m = Math.Min(r, Math.Min(g, b));
            double a = 1.0 - (m / 255.0);
            a = (a - Floor) / (1.0 - Floor);
            if (a <= 0.004) continue;
            o[i]     = Clamp((b - (1 - a) * 255) / a);
            o[i + 1] = Clamp((g - (1 - a) * 255) / a);
            o[i + 2] = Clamp((r - (1 - a) * 255) / a);
            o[i + 3] = Clamp(a * 255);
        }
        return o;
    }

    static byte[] LiftInk(byte[] s) {
        byte[] o = (byte[])s.Clone();
        for (int i = 0; i < o.Length; i += 4) {
            if (o[i + 3] == 0) continue;
            double b = o[i], g = o[i + 1], r = o[i + 2];
            double v = Math.Max(r, Math.Max(g, b));
            if (v >= InkCut) continue;
            double t = (InkCut - v) / InkCut;
            o[i]     = Clamp(b + (LiftB - b) * t);
            o[i + 1] = Clamp(g + (LiftG - g) * t);
            o[i + 2] = Clamp(r + (LiftR - r) * t);
        }
        return o;
    }

    static byte[] Pad(byte[] s, int w, int h, out int W, out int H) {
        W = w + Margin * 2; H = h + Margin * 2;
        byte[] o = new byte[W * H * 4];
        for (int y = 0; y < h; y++)
            Buffer.BlockCopy(s, y * w * 4, o, ((y + Margin) * W + Margin) * 4, w * 4);
        return o;
    }

    static float[] BoxBlur(float[] src, int w, int h, int radius, int passes) {
        float[] a = (float[])src.Clone();
        float[] b = new float[a.Length];
        for (int p = 0; p < passes; p++) {
            for (int y = 0; y < h; y++) {
                float sum = 0; int row = y * w;
                for (int x = -radius; x <= radius; x++) sum += a[row + Math.Min(w - 1, Math.Max(0, x))];
                for (int x = 0; x < w; x++) {
                    b[row + x] = sum / (radius * 2 + 1);
                    sum -= a[row + Math.Min(w - 1, Math.Max(0, x - radius))];
                    sum += a[row + Math.Min(w - 1, Math.Max(0, x + radius + 1))];
                }
            }
            for (int x = 0; x < w; x++) {
                float sum = 0;
                for (int y = -radius; y <= radius; y++) sum += b[Math.Min(h - 1, Math.Max(0, y)) * w + x];
                for (int y = 0; y < h; y++) {
                    a[y * w + x] = sum / (radius * 2 + 1);
                    sum -= b[Math.Min(h - 1, Math.Max(0, y - radius)) * w + x];
                    sum += b[Math.Min(h - 1, Math.Max(0, y + radius + 1)) * w + x];
                }
            }
        }
        return a;
    }

    // Two-radius bloom: a tight bright core plus a wide soft halo, composited under the sharp art.
    static byte[] AddGlow(byte[] s, int w, int h) {
        float[] alpha = new float[w * h];
        for (int i = 0, p = 0; i < s.Length; i += 4, p++) alpha[p] = s[i + 3] / 255f;

        float[] near = BoxBlur(alpha, w, h, 7, 3);
        float[] far  = BoxBlur(alpha, w, h, 22, 3);

        byte[] o = new byte[s.Length];
        for (int i = 0, p = 0; i < s.Length; i += 4, p++) {
            double ag = Math.Min(0.62, near[p] * 0.42 + far[p] * 0.38);
            double al = s[i + 3] / 255.0;
            double ao = al + ag * (1 - al);
            if (ao <= 0.0001) continue;
            double cb = (s[i]     * al + GlowB * ag * (1 - al)) / ao;
            double cg = (s[i + 1] * al + GlowG * ag * (1 - al)) / ao;
            double cr = (s[i + 2] * al + GlowR * ag * (1 - al)) / ao;
            o[i] = Clamp(cb); o[i + 1] = Clamp(cg); o[i + 2] = Clamp(cr); o[i + 3] = Clamp(ao * 255);
        }
        return o;
    }

    static byte Clamp(double v) { return (byte)Math.Max(0, Math.Min(255, Math.Round(v))); }

    public static string Run(string src, string outDir) {
        using (Bitmap input = new Bitmap(src)) {
            int w, h;
            byte[] keyed = KeyWhite(Read(input, out w, out h), w, h);

            int W, H;
            byte[] light = Pad(keyed, w, h, out W, out H);
            Write(outDir + "\\brand_logo.png", light, W, H);

            byte[] dark = AddGlow(Pad(LiftInk(keyed), w, h, out W, out H), W, H);
            Write(outDir + "\\brand_logo_dark.png", dark, W, H);

            return W + "x" + H;
        }
    }
}
"@

Add-Type -TypeDefinition $cs -ReferencedAssemblies System.Drawing
$size = [LogoAssets]::Run($src, $out)
Write-Output "wrote brand_logo.png and brand_logo_dark.png at $size"
