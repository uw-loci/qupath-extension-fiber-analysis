/*
 * tiled_vs_untiled.groovy -- does a tiled run agree with an untiled one?
 *
 * The question no unit test reaches. TileGridTest proves the owned rectangles
 * partition the region and TileScalarAggregationTest proves the scale factor
 * is applied to the right tile, but neither runs the segmenter, so neither can
 * show that a real tiled run returns the same additive totals as the same
 * annotation analysed in one piece.
 *
 * Runs the SAME annotation at the SAME downsample twice: once with the tile
 * budget raised above the region so it goes untiled, once with it lowered so
 * it tiles. Holding the downsample fixed is the whole point -- changing it
 * would change the numbers for legitimate reasons and prove nothing.
 *
 * Requires -Dfiber.tileBudgetPx to be settable, so run it through qp-script
 * with JAVA_OPTS, once per budget.
 *
 * args: projectDir imageNameFilter outDir downsample
 */
import qupath.lib.projects.ProjectIO
import qupath.ext.fiberanalysis.analysis.FiberAnalysisParams
import qupath.ext.fiberanalysis.analysis.FiberAnalysisWorkflow
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences
import java.awt.image.BufferedImage

if (args.size() < 4) { println "USAGE tiled_vs_untiled.groovy projectDir nameFilter outDir downsample"; return }
def project = ProjectIO.loadProject(new File(args[0], "project.qpproj"), BufferedImage.class)
def filter = args[1]
def outDir = new File(args[2]); outDir.mkdirs()
double ds = args[3] as double

// installPreferences() normally runs from the extension's GUI install path,
// which never happens headless, so every property is still null here.
FiberAnalysisPreferences.installPreferences()

// Deterministic settings: the comparison is about tiling, not about tuning.
FiberAnalysisPreferences.analysisDownsampleProperty().set(ds)
FiberAnalysisPreferences.internalChannelProperty().set("Raw intensity")
// Otsu resolves per REGION, so each tile picks its own cut and a tiled run
// segments differently from an untiled one. Pass "Manual" to hold the
// threshold fixed and isolate the aggregation from the segmentation.
def thrMode = System.getProperty("fiber.harness.threshold", "Otsu")
FiberAnalysisPreferences.thresholdMethodProperty().set(thrMode)
if (thrMode == "Manual") {
    FiberAnalysisPreferences.manualThresholdProperty().set(
            Integer.parseInt(System.getProperty("fiber.harness.manual", "4500")))
}
FiberAnalysisPreferences.invertIntensityProperty().set(false)
FiberAnalysisPreferences.windowEnabledProperty().set(true)
FiberAnalysisPreferences.windowSizeUmProperty().set(100.0)
FiberAnalysisPreferences.windowOverlapPercentProperty().set(50)
FiberAnalysisPreferences.windowObjectsProperty().set(false)
FiberAnalysisPreferences.collagenObjectsProperty().set(false)
FiberAnalysisPreferences.jsonSidecarProperty().set(true)
FiberAnalysisPreferences.morphEnabledProperty().set(true)
FiberAnalysisPreferences.straightnessEnabledProperty().set(true)
FiberAnalysisPreferences.textureEnabledProperty().set(false)
FiberAnalysisPreferences.outputDirProperty().set(outDir.getAbsolutePath())

def params = FiberAnalysisParams.fromPreferences()
println "BUDGET ${System.getProperty('fiber.tileBudgetPx', 'default')}"
println "DOWNSAMPLE ${ds}"
println "THRESHOLD ${thrMode}"

for (entry in project.getImageList()) {
    def name = entry.getImageName()
    if (!name.toLowerCase().contains(filter.toLowerCase())) continue
    def imageData = entry.readImageData()
    def anns = new ArrayList(imageData.getHierarchy().getAnnotationObjects())
    if (anns.isEmpty()) { println "SKIP|${name}|no annotations"; continue }
    println "RUN|${name}|${anns.size()} annotation(s)"
    long t0 = System.currentTimeMillis()
    def thread = new FiberAnalysisWorkflow(null).runForAnnotations(params, anns, imageData, null)
    if (thread != null) thread.join()
    println "ELAPSED|${name}|${(System.currentTimeMillis() - t0) / 1000.0}"
}
println "DONE"
