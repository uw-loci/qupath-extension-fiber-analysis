/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis.analysis;

import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferFloat;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

/**
 * A map file served at the size of the image it was computed on, so its
 * channels can be concatenated with that image. Tiles are drawn from the grid
 * on request -- nearest or interpolated, as the file says -- and nothing at
 * full resolution is ever written. Rebuilt from a project through
 * {@link DensityChannelsServerBuilder}, so a saved entry reopens.
 */
public final class DensityChannelsServer extends AbstractTileableImageServer {

    private static final int TILE = 512;

    private final URI uri;
    private final String[] args;
    private final DensityMapReader reader;
    private final DensityMapInfo info;
    private final ImageServerMetadata metadata;
    private final java.awt.image.ColorModel colorModel;

    DensityChannelsServer(URI uri, String... args) throws IOException {
        this.uri = uri;
        this.args = args.clone();
        this.reader = DensityMapReader.open(Path.of(uri));
        this.info = reader.info();
        List<ImageChannel> channels = new ArrayList<>();
        for (var ch : info.channels()) {
            channels.add(ImageChannel.getInstance(ch.name, ImageChannel.getDefaultChannelColor(channels.size())));
        }
        List<Double> downsamples = new ArrayList<>();
        for (double d = 1; Math.max(info.sourceWidth(), info.sourceHeight()) / d >= TILE || d == 1; d *= 2) {
            downsamples.add(d);
        }
        this.metadata = new ImageServerMetadata.Builder(
                        DensityChannelsServer.class, uri.toString(), info.sourceWidth(), info.sourceHeight())
                .name(Path.of(uri).getFileName().toString())
                .pixelType(PixelType.FLOAT32)
                .rgb(false)
                .channels(channels)
                .pixelSizeMicrons(info.sourcePixelUm(), info.sourcePixelUm())
                .preferredTileSize(TILE, TILE)
                .levelsFromDownsamples(
                        downsamples.stream().mapToDouble(Double::doubleValue).toArray())
                .build();
        this.colorModel = ColorModelFactory.createColorModel(PixelType.FLOAT32, channels);
    }

    /** @return the map's placement, render mode and channels */
    public DensityMapInfo getMapInfo() {
        return info;
    }

    @Override
    protected BufferedImage readTile(TileRequest request) throws IOException {
        int outW = request.getTileWidth();
        int outH = request.getTileHeight();
        int n = info.channels().size();
        float[][] banks = new float[n][outW * outH];
        for (float[] b : banks) Arrays.fill(b, Float.NaN);
        fillTile(
                info,
                reader::readBlock,
                banks,
                request.getImageX(),
                request.getImageY(),
                request.getImageWidth(),
                request.getImageHeight(),
                outW,
                outH);
        WritableRaster raster = Raster.createWritableRaster(
                new BandedSampleModel(DataBuffer.TYPE_FLOAT, outW, outH, n),
                new DataBufferFloat(banks, outW * outH),
                null);
        return new BufferedImage(colorModel, raster, false, null);
    }

    /** Reads a block of cells, every channel: {@code [channel][row * w + column]}. */
    interface BlockSource {
        float[][] read(int gx, int gy, int w, int h) throws IOException;
    }

    /**
     * Sample every channel at the centre of each output pixel of a source
     * region, reading only the cells under it.
     *
     * @param info  the map
     * @param cells where the cells come from
     * @param banks output, {@code [channel][outH * outW]}, prefilled with NaN
     * @param x     source x of the region
     * @param y     source y
     * @param w     source width
     * @param h     source height
     * @param outW  output pixels across
     * @param outH  output pixels down
     * @throws IOException if the cells cannot be read
     */
    static void fillTile(
            DensityMapInfo info, BlockSource cells, float[][] banks, int x, int y, int w, int h, int outW, int outH)
            throws IOException {
        DensityLattice lat = info.lattice();
        int cell = lat.cellPx();
        // Cells under the region, plus one on each side so interpolation near
        // the tile edge sees the same neighbours it would on the whole grid.
        int gx0 = Math.max(0, (int) Math.floor((x - lat.x0()) / cell) - 1);
        int gy0 = Math.max(0, (int) Math.floor((y - lat.y0()) / cell) - 1);
        int gx1 = Math.min(lat.gridW() - 1, (int) Math.floor((x + w - lat.x0()) / cell) + 1);
        int gy1 = Math.min(lat.gridH() - 1, (int) Math.floor((y + h - lat.y0()) / cell) + 1);
        if (gx1 < gx0 || gy1 < gy0) return;
        int bw = gx1 - gx0 + 1;
        int bh = gy1 - gy0 + 1;
        float[][] block = cells.read(gx0, gy0, bw, bh);
        DensityLattice sub = new DensityLattice(
                bw, bh, cell, lat.windowPx(), lat.originX() + gx0 * cell, lat.originY() + gy0 * cell);
        double sx = (double) w / outW;
        double sy = (double) h / outH;
        for (int oy = 0; oy < outH; oy++) {
            double v = y + (oy + 0.5) * sy;
            for (int ox = 0; ox < outW; ox++) {
                double u = x + (ox + 0.5) * sx;
                for (int c = 0; c < banks.length; c++) {
                    banks[c][oy * outW + ox] = sub.sample(block[c], u, v, info.smooth(), info.axialPeriod(c));
                }
            }
        }
    }

    @Override
    protected ServerBuilder<BufferedImage> createServerBuilder() {
        // No stored metadata: the channel list is read from the file each time, so a re-run map cannot go stale.
        return DefaultImageServerBuilder.createInstance(DensityChannelsServerBuilder.class, uri, args);
    }

    @Override
    protected String createID() {
        return getClass().getName() + ": " + uri + " " + String.join(" ", args);
    }

    @Override
    public String getServerType() {
        return "Fiber analysis map channels";
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return metadata;
    }

    @Override
    public java.util.Collection<URI> getURIs() {
        return List.of(uri);
    }

    @Override
    public void close() throws Exception {
        super.close();
        reader.close();
    }
}
