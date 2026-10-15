package ai.pollinations.craft;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MapImageTest {

    @Test
    void anyPictureBecomesExactlyOneMap() {
        for (int[] size : new int[][] {{256, 256}, {1024, 576}, {333, 999}, {64, 64}}) {
            BufferedImage source = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
            source.setRGB(0, 0, 0xFFFF0000);
            int[] pixels = MapImage.pixels(source);
            assertEquals(MapImage.SIZE * MapImage.SIZE, pixels.length,
                    size[0] + "x" + size[1] + " became a map");
        }
    }

    @Test
    void aWidePictureKeepsItsShapeInTheMiddle() {
        // A wide banner: white everywhere, one red block across its centre.
        BufferedImage wide = new BufferedImage(256, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = wide.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 256, 64);
        g.setColor(Color.RED);
        g.fillRect(64, 16, 128, 32);
        g.dispose();
        int[] pixels = MapImage.pixels(wide);
        // Scaled to 128x64 and hung in the middle, so the red band lands in
        // the centre rows and the letterbox bars keep the background colour.
        assertEquals(0xFFFFFFFF, pixels[0], "the letterbox bar keeps the background");
        assertEquals(0xFFFF0000, pixels[(MapImage.SIZE / 2) * MapImage.SIZE + MapImage.SIZE / 2],
                "the centre of the picture is red");
        assertNotEquals(0xFFFF0000, pixels[2], "the top corner is above the picture");
    }

    @Test
    void aSquarePictureFillsTheMap() {
        BufferedImage square = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = square.createGraphics();
        g.setColor(new Color(0x123456));
        g.fillRect(0, 0, 512, 512);
        g.dispose();
        int[] pixels = MapImage.pixels(square);
        assertEquals(0xFF123456,
                pixels[(MapImage.SIZE / 2) * MapImage.SIZE + MapImage.SIZE / 2],
                "a square fills the map, middle included");
    }
}
