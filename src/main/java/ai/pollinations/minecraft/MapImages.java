package ai.pollinations.minecraft;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/** Draws generated pictures on maps and keeps them across restarts (plugins/PolliCraft/maps/<id>.png). */
public final class MapImages {

    public static final int MAP_SIZE = 128;

    private final File folder;
    private final Logger logger;

    public MapImages(File folder, Logger logger) {
        this.folder = folder;
        this.logger = logger;
        if (!folder.isDirectory() && !folder.mkdirs()) {
            logger.warning("Could not create " + folder);
        }
    }

    /** Decodes API bytes and scales them to one 128x128 map with a centre crop. */
    public static BufferedImage toMapImage(byte[] bytes) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) {
            throw new IOException("The API did not return an image.");
        }
        return fitSquare(source, MAP_SIZE);
    }

    static BufferedImage fitSquare(BufferedImage source, int size) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage square = source.getSubimage(x, y, side, side);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(square.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return out;
    }

    public void save(int mapId, BufferedImage image) {
        try {
            ImageIO.write(image, "png", file(mapId));
        } catch (IOException e) {
            logger.warning("Could not save map " + mapId + ": " + e.getMessage());
        }
    }

    public BufferedImage load(int mapId) {
        File file = file(mapId);
        if (!file.isFile()) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException e) {
            logger.warning("Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    public boolean has(int mapId) {
        return file(mapId).isFile();
    }

    private File file(int mapId) {
        return new File(folder, mapId + ".png");
    }

    /** Replaces the vanilla renderers of a map with our picture. */
    public static void attach(MapView view, BufferedImage image) {
        for (MapRenderer renderer : view.getRenderers()) {
            view.removeRenderer(renderer);
        }
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        view.addRenderer(new ImageRenderer(image));
    }

    private static final class ImageRenderer extends MapRenderer {
        private final BufferedImage image;
        private volatile boolean drawn;

        ImageRenderer(BufferedImage image) {
            super(false);
            this.image = image;
        }

        @Override
        public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
            if (drawn) {
                return;
            }
            canvas.drawImage(0, 0, image);
            drawn = true;
        }
    }
}
