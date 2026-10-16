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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the channel rows {@link DensityTiffWriter} writes into a sidecar's
 * image description. Kept apart from {@link DensitySidecar} so it carries no
 * Bio-Formats dependency and can be tested on its own.
 */
final class SidecarDescription {

    private static final Logger logger = LoggerFactory.getLogger(SidecarDescription.class);

    private SidecarDescription() {}

    /** Visible for tests: extract channel rows from the OME-XML description block. */
    static List<DensitySidecar.ChannelInfo> parse(String desc) {
        // Row shape (float32, current): [<idx>] name=<name>[; unit=<unit>]
        // Row shape (uint16 legacy):    [<idx>] name=<name>; scale=<s>; offset=<o>[; unit=<unit>]
        // scale/offset are optional -- new sidecars default to 1.0/0.0
        // because the on-disk values are already physical. The name runs to
        // the next ';' or the end of the line: a lazy (.+?) followed only by
        // optional groups matched ONE character, so every channel was named
        // by its first letter.
        Pattern p = Pattern.compile(
                "\\[(\\d+)]\\s*name=([^;\\r\\n]+)(?:;\\s*scale=([\\-+0-9.eE]+);\\s*offset=([\\-+0-9.eE]+))?(?:;\\s*unit=([^\\r\\n;]+))?",
                Pattern.MULTILINE);
        Matcher m = p.matcher(desc);
        List<DensitySidecar.ChannelInfo> out = new ArrayList<>();
        while (m.find()) {
            try {
                int idx = Integer.parseInt(m.group(1));
                String name = m.group(2).trim();
                double scale = m.group(3) == null ? 1.0 : Double.parseDouble(m.group(3));
                double offset = m.group(4) == null ? 0.0 : Double.parseDouble(m.group(4));
                String unit = m.group(5);
                if (unit != null) unit = unit.trim();
                out.add(new DensitySidecar.ChannelInfo(idx, name, scale, offset, unit));
            } catch (NumberFormatException ex) {
                logger.warn("Skipping malformed sidecar channel row: {}", m.group(0));
            }
        }
        out.sort((a, b) -> Integer.compare(a.index, b.index));
        return out;
    }
}
