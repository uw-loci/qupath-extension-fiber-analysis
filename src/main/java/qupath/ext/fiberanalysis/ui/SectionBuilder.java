/*
 * Copyright (c) the QuIET / qupath-extension-image-export-toolkit authors.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
// Copied verbatim from qupath-extension-image-export-toolkit (QuIET) per
// agreement at 2026-05-23 in 02_design.md section 2.
package qupath.ext.fiberanalysis.ui;

import javafx.scene.Node;
import javafx.scene.control.TitledPane;

/**
 * Utility for creating consistently styled collapsible TitledPane sections.
 */
public class SectionBuilder {

    public static TitledPane createSection(String title, boolean expanded, Node content) {
        var tp = new TitledPane(title, content);
        tp.setExpanded(expanded);
        tp.setAnimated(false);
        tp.setCollapsible(true);
        tp.setMaxWidth(Double.MAX_VALUE);
        return tp;
    }
}
