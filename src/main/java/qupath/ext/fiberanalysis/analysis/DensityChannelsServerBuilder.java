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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;

/**
 * Lets QuPath rebuild a {@link DensityChannelsServer} from a saved project.
 * It answers only for a URI carrying {@link #ARG}, so it never competes with
 * the reader of any ordinary image. Registered in
 * {@code META-INF/services/qupath.lib.images.servers.ImageServerBuilder}.
 */
public final class DensityChannelsServerBuilder implements ImageServerBuilder<BufferedImage> {

    /** Marks a map file to be served at its source image's size. */
    public static final String ARG = "--fiber-map-channels";

    /**
     * @param map a density or classifier map file
     * @return the map served at the size of its source image
     * @throws IOException if the file cannot be read
     */
    public static DensityChannelsServer build(Path map) throws IOException {
        return new DensityChannelsServer(map.toUri(), ARG);
    }

    @Override
    public UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args) {
        if (!marked(args)) return UriImageSupport.createInstance(getClass(), 0f, List.of());
        return UriImageSupport.createInstance(
                getClass(), 5f, DefaultImageServerBuilder.createInstance(getClass(), uri, args));
    }

    @Override
    public ImageServer<BufferedImage> buildServer(URI uri, String... args) throws Exception {
        return marked(args) ? new DensityChannelsServer(uri, args) : null;
    }

    @Override
    public String getName() {
        return "Fiber analysis map channels";
    }

    @Override
    public String getDescription() {
        return "Serves a fiber density or classifier map at the size of the image it was computed on";
    }

    @Override
    public Class<BufferedImage> getImageType() {
        return BufferedImage.class;
    }

    private static boolean marked(String... args) {
        return args != null && Arrays.asList(args).contains(ARG);
    }
}
