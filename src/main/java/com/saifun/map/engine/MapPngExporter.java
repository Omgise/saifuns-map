package com.saifun.map.engine;

import com.saifun.map.SaifunsMapMod;
import com.saifun.map.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asynchronous PNG map exporter for the currently active dimension.
 * Stitches both in-memory regions and persisted .smap region files on disk,
 * auto-crops tightly to explored tiles, auto-downscales huge worlds to prevent OOM,
 * and posts a clickable chat link to open the exported PNG directly.
 */
public final class MapPngExporter {

    private static final int MAX_EXPORT_DIM = 8192;
    private static final int CROP_PADDING = 16;
    private static final int BG_ARGB = 0xFF0A0D12;
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.smap$");
    private static final AtomicBoolean EXPORTING = new AtomicBoolean(false);

    private MapPngExporter() {}

    public static void exportCurrentDimensionAsync(MapWorldCache worldCache) {
        if (worldCache == null) return;
        final String worldId = worldCache.getActiveWorldId();
        final int dimId = worldCache.getActiveDimension();
        if (worldId == null || dimId == Integer.MIN_VALUE) {
            postChat(Lang.tr("saifunsmap.export.no_data"));
            return;
        }
        if (!EXPORTING.compareAndSet(false, true)) {
            postChat(Lang.tr("saifunsmap.export.busy"));
            return;
        }

        final Map<Long, MapRegion> loadedSnapshot = worldCache.getLoadedRegionsSnapshot();
        final File dimDir = new File(new File(worldCache.getDataRoot(), "worlds/" + worldId), "dim_" + dimId);
        final File outDir = new File(new File(Minecraft.getMinecraft().mcDataDir, "screenshots"), "saifunsmap");

        postChat(Lang.tr("saifunsmap.export.started", dimId));

        Thread worker = new Thread(() -> {
            try {
                runExport(worldId, dimId, loadedSnapshot, dimDir, outDir);
            } catch (Throwable t) {
                SaifunsMapMod.LOG.error("PNG map export failed", t);
                postChat(Lang.tr("saifunsmap.export.failed", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            } finally {
                EXPORTING.set(false);
            }
        }, "SaifunsMap-PngExport");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runExport(
        String worldId,
        int dimId,
        Map<Long, MapRegion> loadedSnapshot,
        File dimDir,
        File outDir
    ) throws Exception {
        // 1. Gather all region sources (memory first, then disk .smap files)
        Map<Long, int[]> pixelByRegion = new HashMap<>();
        List<long[]> regionCoords = new ArrayList<>();

        for (Map.Entry<Long, MapRegion> e : loadedSnapshot.entrySet()) {
            MapRegion r = e.getValue();
            if (r == null || r.dimensionId != dimId || !r.hasSurfaceData()) continue;
            pixelByRegion.put(e.getKey(), r.copySurfaceShadedArgb());
            regionCoords.add(new long[] { r.regionX, r.regionZ, e.getKey() });
        }

        if (dimDir.exists() && dimDir.isDirectory()) {
            File[] files = dimDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile()) continue;
                    Matcher m = REGION_FILE_PATTERN.matcher(f.getName());
                    if (!m.matches()) continue;
                    int rx = Integer.parseInt(m.group(1));
                    int rz = Integer.parseInt(m.group(2));
                    long key = MapWorldCache.packRegionKey(rx, rz);
                    if (pixelByRegion.containsKey(key)) continue;

                    MapRegion temp = new MapRegion(dimId, rx, rz);
                    if (temp.loadFromDisk(f) && temp.hasSurfaceData()) {
                        pixelByRegion.put(key, temp.copySurfaceShadedArgb());
                        regionCoords.add(new long[] { rx, rz, key });
                    }
                }
            }
        }

        if (regionCoords.isEmpty()) {
            postChat(Lang.tr("saifunsmap.export.no_data"));
            return;
        }

        // 2. Compute tight explored pixel bounds across all collected regions
        int minWx = Integer.MAX_VALUE;
        int maxWx = Integer.MIN_VALUE;
        int minWz = Integer.MAX_VALUE;
        int maxWz = Integer.MIN_VALUE;

        for (long[] rc : regionCoords) {
            int rx = (int) rc[0];
            int rz = (int) rc[1];
            int[] px = pixelByRegion.get(rc[2]);
            int baseWx = rx * MapRegion.SIZE;
            int baseWz = rz * MapRegion.SIZE;
            for (int lz = 0; lz < MapRegion.SIZE; lz++) {
                int rowOff = lz * MapRegion.SIZE;
                for (int lx = 0; lx < MapRegion.SIZE; lx++) {
                    if ((px[rowOff + lx] & 0xFF000000) != 0) {
                        int wx = baseWx + lx;
                        int wz = baseWz + lz;
                        if (wx < minWx) minWx = wx;
                        if (wx > maxWx) maxWx = wx;
                        if (wz < minWz) minWz = wz;
                        if (wz > maxWz) maxWz = wz;
                    }
                }
            }
        }

        if (minWx > maxWx || minWz > maxWz) {
            postChat(Lang.tr("saifunsmap.export.no_data"));
            return;
        }

        minWx -= CROP_PADDING;
        maxWx += CROP_PADDING;
        minWz -= CROP_PADDING;
        maxWz += CROP_PADDING;

        long rawW = (long) maxWx - minWx + 1L;
        long rawH = (long) maxWz - minWz + 1L;
        int step = 1;
        while ((rawW / step) > MAX_EXPORT_DIM || (rawH / step) > MAX_EXPORT_DIM) {
            step *= 2;
        }

        int imgW = Math.max(1, (int) ((rawW + step - 1) / step));
        int imgH = Math.max(1, (int) ((rawH + step - 1) / step));

        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < imgH; y++) {
            for (int x = 0; x < imgW; x++) {
                img.setRGB(x, y, BG_ARGB);
            }
        }

        for (long[] rc : regionCoords) {
            int rx = (int) rc[0];
            int rz = (int) rc[1];
            int[] px = pixelByRegion.get(rc[2]);
            int baseWx = rx * MapRegion.SIZE;
            int baseWz = rz * MapRegion.SIZE;
            for (int lz = 0; lz < MapRegion.SIZE; lz += step) {
                int wz = baseWz + lz;
                if (wz < minWz || wz > maxWz) continue;
                int iy = (wz - minWz) / step;
                if (iy < 0 || iy >= imgH) continue;
                int rowOff = lz * MapRegion.SIZE;
                for (int lx = 0; lx < MapRegion.SIZE; lx += step) {
                    int argb = px[rowOff + lx];
                    if ((argb & 0xFF000000) == 0) continue;
                    int wx = baseWx + lx;
                    if (wx < minWx || wx > maxWx) continue;
                    int ix = (wx - minWx) / step;
                    if (ix >= 0 && ix < imgW) {
                        img.setRGB(ix, iy, argb);
                    }
                }
            }
        }

        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String fileName = "map_" + worldId + "_dim" + dimId + "_" + stamp + ".png";
        File outFile = new File(outDir, fileName);
        ImageIO.write(img, "PNG", outFile);

        postSuccessChat(outFile, imgW, imgH);
    }

    private static void postChat(final String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.func_152344_a(() -> {
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7b[Saifun's Map]\u00a7r " + message));
            }
        });
    }

    private static void postSuccessChat(final File outFile, final int w, final int h) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.func_152344_a(() -> {
            if (mc.thePlayer == null) return;
            ChatComponentText line = new ChatComponentText("");
            ChatComponentText tag = new ChatComponentText("[Saifun's Map] ");
            tag.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.AQUA));
            line.appendSibling(tag);
            line.appendText(Lang.tr("saifunsmap.export.done", w, h) + " ");

            ChatComponentText link = new ChatComponentText("[" + outFile.getName() + "]");
            link.setChatStyle(new ChatStyle()
                .setColor(EnumChatFormatting.GREEN)
                .setUnderlined(true)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, outFile.getAbsolutePath()))
                .setChatHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    new ChatComponentText(Lang.tr("saifunsmap.export.open_hover"))
                )));
            line.appendSibling(link);
            mc.thePlayer.addChatMessage(line);
        });
    }
}
