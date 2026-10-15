package ai.pollinations.craft;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Fits any generated picture into the 128&times;128 pixels of an in-game map.
 * Pure AWT, no Bukkit, so the fitting maths is unit tested directly.
 */
public final class MapImage {

    /** Maps are 128 by 128 pixels in every version that matters here. */
    public static final int SIZE = 128;

    private MapImage() {}

    /**
     * The picture scaled to fit the map, keeping its shape, centred on a
     * background that matches the picture's own corner pixels, so a wide
     * banner image does not end in black bars.
     */
    public static BufferedImage fit(BufferedImage source) {
        BufferedImage out =
                new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        int background = source.getRGB(0, 0);
        g.setColor(new java.awt.Color(background, true));
        g.fillRect(0, 0, SIZE, SIZE);
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        double scale = Math.min((double) SIZE / sourceWidth, (double) SIZE / sourceHeight);
        int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        int height = Math.max(1, (int) Math.round(sourceHeight * scale));
        Image scaled = source.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        g.drawImage(scaled, (SIZE - width) / 2, (SIZE - height) / 2, null);
        g.dispose();
        return out;
    }

    /**
     * The same fit, straight to the pixel array a map renderer paints: rows of
     * RGBA, which is what {@code MapCanvas#setPixel} takes.
     */
    public static int[] pixels(BufferedImage source) {
        BufferedImage fitted = fit(source);
        int[] pixels = new int[SIZE * SIZE];
        fitted.getRGB(0, 0, SIZE, SIZE, pixels, 0, SIZE);
        return pixels;
    }

    /** Cheap identity for tests and logs. */
    public static String describe(int[] pixels) {
        return pixels.length + "px, first=" + Integer.toHexString(pixels[0])
                + ", spread=" + Arrays.stream(pixels).distinct().count();
    }
}
