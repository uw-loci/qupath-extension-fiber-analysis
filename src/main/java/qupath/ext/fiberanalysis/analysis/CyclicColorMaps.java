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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.color.ColorMaps;

/**
 * Cyclic colour maps for an axial angle, installed into QuPath's colour-map
 * registry (so they also appear in measurement maps and in QuIET). A cyclic
 * map ends where it starts, so 1 and 179 degrees get the same colour.
 *
 * <ul>
 *   <li><b>Angle (cyclic)</b>: a circle of constant lightness in CIELAB
 *       (L*=65, C*=32); hue alone carries the angle, so no orientation looks
 *       more prominent. Use it to judge orientation.</li>
 *   <li><b>Angle (cyclic vivid)</b>: the saturated hue wheel; easier to read,
 *       but its lightness varies. Use it to show results.</li>
 * </ul>
 *
 * <p>Copied from TME Quant ({@code qupath-extension-tme-quant},
 * {@code CyclicColorMaps}, commit a75e974, Apache-2.0), with the same names so
 * installing both extensions registers each map once.
 */
public final class CyclicColorMaps {

    private static final Logger logger = LoggerFactory.getLogger(CyclicColorMaps.class);

    /** The constant-lightness map's name. */
    public static final String CYCLIC = "Angle (cyclic)";
    /** The hue wheel's name. */
    public static final String CYCLIC_VIVID = "Angle (cyclic vivid)";

    /** Entries in each lookup table; the last repeats the first, which closes the cycle. */
    private static final int N = 256;

    private static final double LAB_L = 65.0;
    private static final double LAB_C = 32.0;

    private CyclicColorMaps() {}

    /** Register both maps; a failure is logged and never stops the extension loading. */
    public static void install() {
        try {
            ColorMaps.installColorMaps(labCircle(CYCLIC), hueWheel(CYCLIC_VIVID));
        } catch (Exception | LinkageError ex) {
            logger.warn("Could not install the cyclic angle colour maps: {}", ex.getMessage());
        }
    }

    /** @return true for a map that ends where it starts */
    public static boolean isCyclic(String name) {
        return CYCLIC.equals(name) || CYCLIC_VIVID.equals(name);
    }

    static ColorMaps.ColorMap labCircle(String name) {
        double[] r = new double[N];
        double[] g = new double[N];
        double[] b = new double[N];
        for (int i = 0; i < N; i++) {
            double h = 2 * Math.PI * i / (N - 1.0);
            double[] rgb = labToSrgb(LAB_L, LAB_C * Math.cos(h), LAB_C * Math.sin(h));
            r[i] = rgb[0];
            g[i] = rgb[1];
            b[i] = rgb[2];
        }
        return ColorMaps.createColorMap(name, r, g, b);
    }

    static ColorMaps.ColorMap hueWheel(String name) {
        double[] r = new double[N];
        double[] g = new double[N];
        double[] b = new double[N];
        for (int i = 0; i < N; i++) {
            int argb = java.awt.Color.HSBtoRGB((float) (i / (N - 1.0)), 1f, 1f);
            r[i] = ((argb >> 16) & 0xFF) / 255.0;
            g[i] = ((argb >> 8) & 0xFF) / 255.0;
            b[i] = (argb & 0xFF) / 255.0;
        }
        return ColorMaps.createColorMap(name, r, g, b);
    }

    /** CIELAB (D65) to sRGB, 0-1; the L*=65, C*=32 circle lies inside the sRGB gamut. */
    private static double[] labToSrgb(double lStar, double aStar, double bStar) {
        double fy = (lStar + 16.0) / 116.0;
        double fx = fy + aStar / 500.0;
        double fz = fy - bStar / 200.0;
        double x = 0.95047 * labInv(fx);
        double y = 1.00000 * labInv(fy);
        double z = 1.08883 * labInv(fz);
        return new double[] {
            gamma(3.2406 * x - 1.5372 * y - 0.4986 * z),
            gamma(-0.9689 * x + 1.8758 * y + 0.0415 * z),
            gamma(0.0557 * x - 0.2040 * y + 1.0570 * z),
        };
    }

    private static double labInv(double t) {
        return t > 6.0 / 29.0 ? t * t * t : 3.0 * (6.0 / 29.0) * (6.0 / 29.0) * (t - 4.0 / 29.0);
    }

    private static double gamma(double c) {
        double v = Math.min(1.0, Math.max(0.0, c));
        return v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
    }
}
