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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;

/**
 * Provenance utility shared by every file-producing analysis in this
 * extension. Emits three companion artifacts next to each run's outputs so
 * the run can be (a) audited from disk alone and (b) re-executed headlessly
 * with no GUI clicks:
 *
 * <ul>
 *   <li>{@code params.json} -- machine-readable, pretty-printed JSON with a
 *       top-level {@code meta} block ({@code versions}, {@code run},
 *       {@code parameters}). The {@code parameters} block is the same
 *       insertion-ordered map each workflow already builds for its own
 *       sidecar; here we just wrap it with provenance.</li>
 *   <li>{@code params.txt} -- human-readable echo, {@code key = value} per
 *       line. Group headers are inserted by the caller via the
 *       {@code headerComment} argument so a reader sees fields organised
 *       by dialog section rather than alphabetically.</li>
 *   <li>{@code rerun.groovy} -- a self-contained Groovy script that loads
 *       the sibling {@code params.json} and re-executes the analysis via
 *       {@code QuPath script ...}. The template is bundled as a JAR
 *       resource under {@code rerun/} and rendered with simple
 *       {@code ${VAR}} substitution -- no templating dependency.</li>
 * </ul>
 *
 * <p>The three writers are independent; a caller can emit one without
 * emitting the others. {@link FiberAnalysisWorkflow} replaces its inline
 * {@code writeParamsFiles} with calls here; {@link FiberDensityMapWorkflow}
 * and {@link FiberCalibrationRunner} adopt the same triple for parity.
 */
public final class RunProvenance {

    private static final Logger logger = LoggerFactory.getLogger(RunProvenance.class);

    /** Resource path prefix for the bundled Groovy rerun templates. */
    private static final String TEMPLATE_BASE = "qupath/ext/fiberanalysis/rerun/";

    /** Workflow-kind tags used to pick a template + the {@code workflow} field in JSON. */
    public static final String KIND_FIBER_ANALYSIS = "fiber_analysis";

    public static final String KIND_DENSITY_MAP = "density_map";
    public static final String KIND_CALIBRATION = "calibration";

    private RunProvenance() {}

    /**
     * Write {@code params.json} (the path is the file itself, not a directory).
     *
     * <p>The caller's {@code parameters} map is wrapped with a
     * {@code meta} block: {@code {versions, run: {timestamp_utc, run_id,
     * extension_version, image_name, workflow}, parameters: ...}}. The
     * {@code versions} block reports only the Java-side extension version
     * here -- the Python side adds its own {@code versions} block to
     * {@code windows.json} when fiberlib runs, so consumers should consult
     * both sidecars for the full picture.
     */
    public static void writeParamsJson(
            Path outFile,
            String workflowKind,
            Map<String, Object> parameters,
            String imageName,
            String runStamp,
            String paramsHash)
            throws IOException {

        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> versions = new LinkedHashMap<>();
        String extVersion = GeneralTools.getPackageVersion(RunProvenance.class);
        versions.put("extension", extVersion != null ? extVersion : "dev");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("versions", versions);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("workflow", workflowKind);
        run.put("timestamp_utc", OffsetDateTime.now().toString());
        run.put("run_id", UUID.randomUUID().toString());
        if (runStamp != null) run.put("run_stamp", runStamp);
        if (paramsHash != null) run.put("params_hash", paramsHash);
        if (imageName != null) run.put("image_name", imageName);
        meta.put("run", run);

        root.put("meta", meta);
        root.put("parameters", parameters);

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(outFile, gson.toJson(root));
    }

    /**
     * Write {@code params.txt} (human-readable echo).
     *
     * @param outFile       the file to write
     * @param headerComment lines to prepend (each prefixed with "# "); pass
     *                      e.g. usage hints + section group names.
     * @param parameters    same map that went into {@link #writeParamsJson}.
     */
    public static void writeParamsTxt(Path outFile, List<String> headerComment, Map<String, Object> parameters)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        if (headerComment != null) {
            for (String line : headerComment) {
                sb.append("# ").append(line).append('\n');
            }
        }
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            sb.append(e.getKey())
                    .append(" = ")
                    .append(formatTxtValue(e.getValue()))
                    .append('\n');
        }
        Files.writeString(outFile, sb.toString());
    }

    private static String formatTxtValue(Object v) {
        if (v == null) return "";
        if (v instanceof Map<?, ?> m) {
            // Inline one-level nested maps as {k1=v1, k2=v2} so the txt
            // form stays line-per-key and grep-friendly.
            return m.entrySet().stream()
                    .map(en -> en.getKey() + "=" + formatTxtValue(en.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        return String.valueOf(v);
    }

    /**
     * Write {@code rerun.groovy} from a bundled template.
     *
     * <p>The template is loaded by {@code workflowKind}:
     * <ul>
     *   <li>{@link #KIND_FIBER_ANALYSIS} -> {@code rerun_fiber_analysis.groovy.template}</li>
     *   <li>{@link #KIND_DENSITY_MAP} -> {@code rerun_density_map.groovy.template}</li>
     *   <li>{@link #KIND_CALIBRATION} -> {@code rerun_calibration.groovy.template}</li>
     * </ul>
     *
     * <p>{@code placeholders} maps token names (without the {@code ${...}}
     * braces) to literal string values. The template's own
     * {@code ${TIMESTAMP_UTC}} and {@code ${EXTENSION_VERSION}} are filled
     * in automatically.
     */
    public static void writeRerunGroovy(Path outFile, String workflowKind, Map<String, String> placeholders)
            throws IOException {
        String templateName = "rerun_" + workflowKind + ".groovy.template";
        String body = loadResource(TEMPLATE_BASE + templateName);

        Map<String, String> all = new LinkedHashMap<>();
        if (placeholders != null) all.putAll(placeholders);
        all.putIfAbsent("TIMESTAMP_UTC", OffsetDateTime.now().toString());
        String extVersion = GeneralTools.getPackageVersion(RunProvenance.class);
        all.putIfAbsent("EXTENSION_VERSION", extVersion != null ? extVersion : "dev");
        all.putIfAbsent("WORKFLOW_KIND", workflowKind);

        for (Map.Entry<String, String> e : all.entrySet()) {
            body = body.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }

        // Sanity check: warn (not fail) if any unsubstituted ${TOKEN} remains.
        int leftover = countUnsubstituted(body);
        if (leftover > 0) {
            logger.warn(
                    "Rerun template '{}' has {} unsubstituted ${{...}} token(s) -- check the placeholder map",
                    templateName,
                    leftover);
        }

        Files.writeString(outFile, body);
    }

    /**
     * Convenience: emit all three artifacts ({@code params.json},
     * {@code params.txt}, {@code rerun.groovy}) with consistent base names.
     *
     * <p>If {@code baseName} is "params" the files land as {@code params.json},
     * {@code params.txt}, {@code rerun.groovy}. If "abc_density" they land as
     * {@code abc_density_params.json}, {@code abc_density_params.txt},
     * {@code abc_density_rerun.groovy}. Callers pick the basename to match
     * existing file-naming conventions in their output dir.
     *
     * @param outDir         parent directory; created if missing.
     * @param baseName       filename stem ({@code "params"} or {@code "<image>_density"}).
     * @param workflowKind   one of the {@code KIND_*} constants.
     * @param parameters     insertion-ordered map of all settings.
     * @param txtHeader      lines to put at the top of params.txt (each
     *                       prefixed with "# "); pass null for none.
     * @param imageName      source image name (logged in meta.run).
     * @param runStamp       optional stamp string (e.g. "20260608_120000");
     *                       logged in meta.run if non-null.
     * @param paramsHash     optional fingerprint; logged in meta.run if non-null.
     * @param placeholders   token map for the Groovy template body
     *                       (e.g. {@code PROJECT_PATH}, {@code IMAGE_NAME},
     *                       {@code PARAMS_JSON_PATH}).
     */
    public static void writeAll(
            Path outDir,
            String baseName,
            String workflowKind,
            Map<String, Object> parameters,
            List<String> txtHeader,
            String imageName,
            String runStamp,
            String paramsHash,
            Map<String, String> placeholders)
            throws IOException {
        Files.createDirectories(outDir);
        // All three files share the same stem so they sort together in a
        // file listing: "params.json", "params.txt", "rerun.groovy" when
        // baseName=="params"; "abc_density_params.json",
        // "abc_density_params.txt", "abc_density_rerun.groovy" otherwise.
        Path jsonPath = outDir.resolve(stemForParams(baseName) + ".json");
        Path txtPath = outDir.resolve(stemForParams(baseName) + ".txt");
        Path groovyPath = outDir.resolve(stemForRerun(baseName) + ".groovy");

        writeParamsJson(jsonPath, workflowKind, parameters, imageName, runStamp, paramsHash);
        writeParamsTxt(txtPath, txtHeader, parameters);

        // Inject the resolved PARAMS_JSON_PATH placeholder if the caller
        // didn't already set it -- the rerun script needs the absolute
        // path to load itself back. Both the raw string and a
        // Groovy-quoted "_LITERAL" form are exposed so templates can
        // splice either directly into a `String x = ${...}` declaration
        // or into a comment line without worrying about quoting.
        Map<String, String> ph = placeholders == null ? new LinkedHashMap<>() : new LinkedHashMap<>(placeholders);
        String absJson = jsonPath.toAbsolutePath().toString();
        ph.putIfAbsent("PARAMS_JSON_PATH", absJson);
        ph.putIfAbsent("PARAMS_JSON_PATH_LITERAL", groovyString(absJson));
        writeRerunGroovy(groovyPath, workflowKind, ph);
    }

    /**
     * Stem for {@code params.json}/{@code params.txt}.
     * baseName=="params" -> "params"; baseName=="abc_density" -> "abc_density_params".
     */
    private static String stemForParams(String base) {
        if (base.equals("params")) return base;
        return base + "_params";
    }

    private static String stemForRerun(String base) {
        if (base.equals("params")) return "rerun";
        return base + "_rerun";
    }

    private static String loadResource(String resourcePath) throws IOException {
        try (InputStream is = RunProvenance.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("Resource not found: " + resourcePath);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return r.lines().collect(Collectors.joining("\n", "", "\n"));
            }
        }
    }

    private static int countUnsubstituted(String body) {
        // Only count ALL-CAPS-WITH-UNDERSCORES tokens as placeholders. Groovy
        // GStrings in the template body (e.g. `${paramsJsonPath}` inside a
        // println line) use lower-case variable names and would otherwise
        // trip a false-positive warning every time.
        int count = 0;
        int i = 0;
        while ((i = body.indexOf("${", i)) >= 0) {
            int end = body.indexOf('}', i + 2);
            if (end < 0) break;
            String token = body.substring(i + 2, end);
            if (!token.isEmpty()
                    && token.chars().allMatch(c -> (c >= 'A' && c <= 'Z') || c == '_' || (c >= '0' && c <= '9'))) {
                count++;
            }
            i = end + 1;
        }
        return count;
    }

    /**
     * Build a quoted-comma-separated literal suitable for Groovy
     * {@code [...]} list placeholders. Each item is single-quoted and
     * apostrophes are backslash-escaped, so a name containing
     * {@code "Bob's annotation"} round-trips safely.
     */
    public static String groovyStringList(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        List<String> quoted = new ArrayList<>(items.size());
        for (String s : items) {
            String safe = s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'");
            quoted.add("'" + safe + "'");
        }
        return String.join(", ", quoted);
    }

    /**
     * Build a Groovy single-quoted string literal for a path argument.
     * Escapes backslashes (Windows path separators) and apostrophes.
     */
    public static String groovyString(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** Locale-stable comment line used in Groovy template headers. */
    public static String generatedHeaderLine() {
        String extVersion = GeneralTools.getPackageVersion(RunProvenance.class);
        return String.format(
                Locale.ROOT,
                "// Generated by qupath-extension-fiber-analysis v%s at %s",
                extVersion != null ? extVersion : "dev",
                OffsetDateTime.now());
    }
}
