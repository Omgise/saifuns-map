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
        // 1. Gather region sources without holding all disk region int[] buffers in RAM simultaneously!
        Map<Long, int[]> memoryPixels = new HashMap<>();
        List<long[]> memoryCoords = new ArrayList<>();
        List<Object[]> diskRegions = new ArrayList<>(); // [rx, rz, File]

        for (Map.Entry<Long, MapRegion> e : loadedSnapshot.entrySet()) {
            MapRegion r = e.getValue();
            if (r == null || r.dimensionId != dimId || !r.hasSurfaceData()) continue;
            memoryPixels.put(e.getKey(), r.copySurfaceShadedArgb());
            memoryCoords.add(new long[] { r.regionX, r.regionZ, e.getKey() });
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
                    if (memoryPixels.containsKey(key)) continue;
                    diskRegions.add(new Object[] { rx, rz, f });
                }
            }
        }

        if (memoryCoords.isEmpty() && diskRegions.isEmpty()) {
            postChat(Lang.tr("saifunsmap.export.no_data"));
            return;
        }

        // 2. Pass 1: Compute tight explored pixel bounds (streaming disk files one at a time -> low peak RAM!)
        int[] bounds = new int[] { Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE };

        for (long[] rc : memoryCoords) {
            updateBounds(bounds, (int) rc[0], (int) rc[1], memoryPixels.get(rc[2]));
        }
        List<Object[]> validDiskRegions = new ArrayList<>(diskRegions.size());
        for (Object[] dr : diskRegions) {
            int rx = (Integer) dr[0];
            int rz = (Integer) dr[1];
            File f = (File) dr[2];
            MapRegion temp = new MapRegion(dimId, rx, rz);
            if (temp.loadFromDisk(f) && temp.hasSurfaceData()) {
                updateBounds(bounds, rx, rz, temp.copySurfaceShadedArgb());
                validDiskRegions.add(dr);
            }
        }

        int minWx = bounds[0];
        int maxWx = bounds[1];
        int minWz = bounds[2];
        int maxWz = bounds[3];
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

        // 3. Pass 2: Blit memory regions (releasing each buffer immediately) and stream disk regions one by one
        for (long[] rc : memoryCoords) {
            int[] px = memoryPixels.remove(rc[2]);
            if (px != null) {
                blitRegion(img, imgW, imgH, (int) rc[0], (int) rc[1], px, minWx, maxWx, minWz, maxWz, step);
            }
        }
        for (Object[] dr : validDiskRegions) {
            int rx = (Integer) dr[0];
            int rz = (Integer) dr[1];
            File f = (File) dr[2];
            MapRegion temp = new MapRegion(dimId, rx, rz);
            if (temp.loadFromDisk(f) && temp.hasSurfaceData()) {
                blitRegion(img, imgW, imgH, rx, rz, temp.copySurfaceShadedArgb(), minWx, maxWx, minWz, maxWz, step);
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

    private static void updateBounds(int[] bounds, int rx, int rz, int[] px) {
        int baseWx = rx * MapRegion.SIZE;
        int baseWz = rz * MapRegion.SIZE;
        for (int lz = 0; lz < MapRegion.SIZE; lz++) {
            int rowOff = lz * MapRegion.SIZE;
            for (int lx = 0; lx < MapRegion.SIZE; lx++) {
                if ((px[rowOff + lx] & 0xFF000000) != 0) {
                    int wx = baseWx + lx;
                    int wz = baseWz + lz;
                    if (wx < bounds[0]) bounds[0] = wx;
                    if (wx > bounds[1]) bounds[1] = wx;
                    if (wz < bounds[2]) bounds[2] = wz;
                    if (wz > bounds[3]) bounds[3] = wz;
                }
            }
        }
    }

    private static void blitRegion(
        BufferedImage img, int imgW, int imgH,
        int rx, int rz, int[] px,
        int minWx, int maxWx, int minWz, int maxWz, int step
    ) {
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
