package ai.pollinations.craft;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Generated pictures as in-game maps. Every map is saved as a PNG in the
 * plugin folder, so the picture a player holds is still there after a
 * restart: the plugin re-attaches a renderer to each saved map on enable.
 */
public final class MapGallery {

    private final File mapsFolder;

    public MapGallery(File dataFolder) {
        this.mapsFolder = new File(dataFolder, "maps");
    }

    /** A fresh map showing the picture, ready to hand to a player. */
    public ItemStack paint(World world, BufferedImage picture) throws IOException {
        MapView view = Bukkit.createMap(world);
        for (MapRenderer old : view.getRenderers()) view.removeRenderer(old);
        BufferedImage fitted = MapImage.fit(picture);
        view.setScale(MapView.Scale.NORMAL);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.addRenderer(new PictureRenderer(fitted));
        File file = new File(mapsFolder, view.getId() + ".png");
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new IOException("cannot create " + file.getParentFile());
        }
        ImageIO.write(fitted, "png", file);
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.setDisplayName("Pollinations picture");
        item.setItemMeta(meta);
        return item;
    }

    /** Re-attach every saved picture, called once on enable. */
    public int restore() {
        File[] files = mapsFolder.listFiles((folder, name) -> name.endsWith(".png"));
        if (files == null) return 0;
        int restored = 0;
        for (File file : files) {
            try {
                int id = Integer.parseInt(file.getName().replace(".png", ""));
                MapView view = Bukkit.getMap(id);
                if (view == null) continue;
                BufferedImage picture = ImageIO.read(file);
                if (picture == null) continue;
                for (MapRenderer old : view.getRenderers()) view.removeRenderer(old);
                view.setScale(MapView.Scale.NORMAL);
                view.addRenderer(new PictureRenderer(picture));
                restored++;
            } catch (IOException | NumberFormatException e) {
                Bukkit.getLogger().log(
                        Level.WARNING, "pollinations-craft: cannot restore " + file.getName(), e);
            }
        }
        return restored;
    }

    /** Hands the player a map, one inventory slot each time. */
    public void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
    }

    /** Paints one fixed picture. The pixels are already 128 by 128. */
    private static final class PictureRenderer extends MapRenderer {

        private final int[] pixels;
        private boolean painted;

        PictureRenderer(BufferedImage picture) {
            this.pixels = MapImage.pixels(picture);
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (painted) return;
            painted = true;
            for (int y = 0; y < MapImage.SIZE; y++) {
                for (int x = 0; x < MapImage.SIZE; x++) {
                    canvas.setPixelColor(x, y, new java.awt.Color(pixels[y * MapImage.SIZE + x], true));
                }
            }
        }
    }
}
