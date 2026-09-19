import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Regenerates every MyExodus icon asset from the brand master (white-background JPEG).
 * Run from the repo root:  java scripts/GenerateIcons.java
 *
 * Keying strategy: the master is a JPEG, so "no background" is not literal — the white is
 * opaque and has to be keyed out. Alpha ramps from the Chebyshev distance to white, and the
 * surviving colour is un-premultiplied off that white so edge pixels don't carry a pale halo.
 * A global threshold (not a border flood-fill) is correct here because the design has white
 * *inside* it too — the separation gap where the stem crosses the leaves is background.
 */
public class GenerateIcons {
    static final File MASTER = new File("assets/brand/myexodus-logo-master.jpeg");
    static final File RES = new File("app/src/main/res");

    static final int CLEAR_BELOW = 30;   // distance to white that becomes fully transparent
    static final int SOLID_ABOVE = 80;   // distance that becomes fully opaque

    /** density bucket name -> scale, sized in dp: 108dp adaptive canvas. */
    static final String[] BUCKETS = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    static final float[] SCALES = {1f, 1.5f, 2f, 3f, 4f};

    // Share of the canvas the mark's bounding-box diagonal may occupy. Keeps the pointy leaf
    // tips inside the 66/108dp safe circle, so no OEM mask crops them.
    static final double ADAPTIVE_DIAGONAL = 0.60;

    public static void main(String[] args) throws Exception {
        BufferedImage master = ImageIO.read(MASTER);
        BufferedImage keyed = keyOutWhite(master);
        System.out.printf("master %dx%d keyed%n", master.getWidth(), master.getHeight());

        int[] split = findWordmarkGap(keyed);
        System.out.printf("ink rows [%d..%d], gap [%d..%d]%n", split[0], split[1], split[2], split[3]);

        BufferedImage mark = trim(keyed.getSubimage(0, split[0], keyed.getWidth(), split[2] - split[0]));
        BufferedImage lockup = trim(keyed.getSubimage(0, split[0], keyed.getWidth(), split[1] - split[0]));
        System.out.printf("mark %dx%d, lockup %dx%d%n",
                mark.getWidth(), mark.getHeight(), lockup.getWidth(), lockup.getHeight());

        write(fit(mark, 256), new File(RES, "drawable-nodpi/logo_mark.png"));
        write(fit(lockup, 320), new File(RES, "drawable-nodpi/logo_lockup.png"));

        // No legacy raster launchers: minSdk 26 (ADR #26) means the adaptive icon in
        // mipmap-anydpi is the only resource the launcher can resolve, so pre-26 PNGs
        // would be unreachable dead weight (removed 2026-09-19).
        for (int i = 0; i < BUCKETS.length; i++) {
            write(adaptive(mark, Math.round(108 * SCALES[i])), mipmap(i, "ic_launcher_foreground.png"));
        }
        System.out.println("done");
    }

    static File mipmap(int bucket, String name) {
        return new File(RES, "mipmap-" + BUCKETS[bucket] + "/" + name);
    }

    /** Replaces the white field with alpha, ramping across the antialiased edge. */
    static BufferedImage keyOutWhite(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                int d = Math.max(Math.max(255 - r, 255 - g), 255 - b);
                if (d <= CLEAR_BELOW) {
                    continue; // leave fully transparent
                }
                int a = d >= SOLID_ABOVE ? 255 : (d - CLEAR_BELOW) * 255 / (SOLID_ABOVE - CLEAR_BELOW);
                float f = a / 255f;
                // coloured = (composite - white * (1 - alpha)) / alpha
                int cr = unpremultiply(r, f), cg = unpremultiply(g, f), cb = unpremultiply(b, f);
                out.setRGB(x, y, (a << 24) | (cr << 16) | (cg << 8) | cb);
            }
        }
        return out;
    }

    static int unpremultiply(int channel, float alpha) {
        return Math.max(0, Math.min(255, Math.round((channel - 255 * (1 - alpha)) / alpha)));
    }

    /** Returns {inkTop, inkBottom, gapStart, gapEnd} — the band separating mark from wordmark. */
    static int[] findWordmarkGap(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] rowInk = new int[h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) >>> 24) > 16) rowInk[y]++;
            }
        }
        int minInk = Math.max(1, w / 200);
        int top = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            if (rowInk[y] >= minInk) {
                if (top < 0) top = y;
                bottom = y;
            }
        }
        if (top < 0) throw new IllegalStateException("no ink found — keying threshold too aggressive");

        int from = top + (int) ((bottom - top) * 0.40);
        int to = top + (int) ((bottom - top) * 0.92);
        int bestStart = -1, bestLen = 0, run = 0;
        for (int y = from; y <= to; y++) {
            if (rowInk[y] < minInk) {
                if (run == 0) run = y;
            } else if (run > 0) {
                if (y - run > bestLen) { bestLen = y - run; bestStart = run; }
                run = 0;
            }
        }
        if (bestLen < 4) throw new IllegalStateException("no gap between mark and wordmark — split manually");
        return new int[]{top, bottom, bestStart, bestStart + bestLen};
    }

    /** Crops to the bounding box of non-transparent pixels. */
    static BufferedImage trim(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) >>> 24) > 16) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** Scales to fit maxSide, halving first so the bicubic pass never decimates hard. */
    static BufferedImage fit(BufferedImage src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxSide && h <= maxSide) return src;
        double f = (double) maxSide / Math.max(w, h);
        int tw = Math.max(1, (int) Math.round(w * f)), th = Math.max(1, (int) Math.round(h * f));
        BufferedImage cur = src;
        while (cur.getWidth() >= tw * 2 && cur.getHeight() >= th * 2) {
            cur = redraw(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return redraw(cur, tw, th);
    }

    static BufferedImage redraw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(out);
        g.drawImage(src, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        return out;
    }

    /** Adaptive foreground: transparent 108dp canvas, mark inside the safe circle. */
    static BufferedImage adaptive(BufferedImage mark, int canvas) {
        BufferedImage out = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(out);
        drawFitted(g, mark, canvas, ADAPTIVE_DIAGONAL * canvas);
        g.dispose();
        return out;
    }

    static void drawFitted(Graphics2D g, BufferedImage art, int canvas, double maxDiagonal) {
        double diag = Math.hypot(art.getWidth(), art.getHeight());
        double f = Math.min(maxDiagonal / diag, (double) canvas / Math.max(art.getWidth(), art.getHeight()));
        int w = (int) Math.round(art.getWidth() * f), h = (int) Math.round(art.getHeight() * f);
        BufferedImage scaled = fit(art, Math.max(w, h));
        g.drawImage(scaled, (canvas - scaled.getWidth()) / 2, (canvas - scaled.getHeight()) / 2, null);
    }

    static Graphics2D quality(BufferedImage target) {
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }

    static void write(BufferedImage img, File out) throws Exception {
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.printf("  %-52s %dx%d%n", out.getPath(), img.getWidth(), img.getHeight());
    }
}
