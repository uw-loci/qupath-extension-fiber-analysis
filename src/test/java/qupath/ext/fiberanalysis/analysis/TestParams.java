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

import java.lang.reflect.RecordComponent;
import java.util.Map;

/** Run parameters for tests: the named components set, every other one a neutral value. */
final class TestParams {

    private TestParams() {}

    /**
     * @param values component name to value
     * @return the parameters
     */
    static FiberAnalysisParams with(Map<String, Object> values) {
        RecordComponent[] cs = FiberAnalysisParams.class.getRecordComponents();
        Object[] args = new Object[cs.length];
        Class<?>[] types = new Class<?>[cs.length];
        for (int i = 0; i < cs.length; i++) {
            Class<?> t = cs[i].getType();
            types[i] = t;
            Object v = values.get(cs[i].getName());
            if (v != null) args[i] = v;
            else if (t == String.class) args[i] = "";
            else if (t == int.class) args[i] = 1;
            else if (t == double.class) args[i] = 1.0;
            else if (t == boolean.class) args[i] = false;
            else throw new IllegalStateException("Unhandled type " + t + " for " + cs[i].getName());
        }
        try {
            return FiberAnalysisParams.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
