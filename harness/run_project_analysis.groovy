/*
 * run_project_analysis.groovy -- fiber analysis over every matching annotation
 * in a project, headless.
 *
 * Uses the project-calibrated threshold, which is the point: per-region Otsu
 * resolves a separate cut inside every tile, so a tiled run disagrees with an
 * untiled one and tiles disagree with each other. One calibrated number makes
 * tiles, images and slides comparable.
 *
 * Never calls project.syncChanges(), so a project whose image URIs were
 * remapped for WSL keeps its original Windows paths on disk.
 *
 * args: projectDir calibrationName outDir [nameFilter] [downsample] [windowUm]
 */
import qupath.lib.projects.ProjectIO
import qupath.ext.fiberanalysis.analysis.FiberAnalysisParams
import qupath.ext.fiberanalysis.analysis.FiberAnalysisWorkflow
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences
import java.awt.image.BufferedImage

if (args.size() < 3) { println "USAGE run_project_analysis.groovy projectDir calibrationName outDir [nameFilter] [downsample] [windowUm]"; return }
FiberAnalysisPreferences.installPreferences()

def project = ProjectIO.loadProject(new File(args[0], "project.qpproj"), BufferedImage.class)
def calName = args[1]
def outDir  = new File(args[2]); outDir.mkdirs()
def filter  = args.size() > 3 && args[3] != "-" ? args[3] : null
double ds   = args.size() > 4 ? args[4] as double : 1.0
double winUm = args.size() > 5 ? args[5] as double : 100.0

for (entry in project.getImageList()) {
    def map = [:]
    for (u in entry.getURIs()) {
        def m = (u.toString() =~ /file:\/{2,}([A-Za-z]):\/(.*)/)
        if (m.find()) {
            def f = new File("/mnt/" + m.group(1).toLowerCase() + "/" +
                    java.net.URLDecoder.decode(m.group(2), "UTF-8"))
            if (f.exists()) map[u] = f.toURI()
        }
    }
    if (!map.isEmpty()) entry.updateURIs(map)
}

FiberAnalysisPreferences.analysisDownsampleProperty().set(ds)
FiberAnalysisPreferences.internalChannelProperty().set("Raw intensity")
FiberAnalysisPreferences.thresholdMethodProperty().set("Project Otsu (calibrated)")
FiberAnalysisPreferences.projectCalibrationNameProperty().set(calName)
FiberAnalysisPreferences.invertIntensityProperty().set(false)
// These annotations ARE the tissue, so measure the whole interior. The band
// modes measure from the boundary; the default "outside" band falls in the
// unacquired background and reported 0.56% coverage on a region that was
// 43.3% fiber.
FiberAnalysisPreferences.zoneModeProperty().set("whole")
FiberAnalysisPreferences.windowEnabledProperty().set(true)
FiberAnalysisPreferences.windowSizeUmProperty().set(winUm)
FiberAnalysisPreferences.windowOverlapPercentProperty().set(50)
// Leave the user's hierarchy alone: a batch run should not silently inject
// tens of thousands of detections into a project it does not own.
FiberAnalysisPreferences.windowObjectsProperty().set(false)
FiberAnalysisPreferences.collagenObjectsProperty().set(false)
FiberAnalysisPreferences.jsonSidecarProperty().set(true)
FiberAnalysisPreferences.straightnessEnabledProperty().set(true)
FiberAnalysisPreferences.morphEnabledProperty().set(true)
FiberAnalysisPreferences.textureEnabledProperty().set(true)
FiberAnalysisPreferences.outputDirProperty().set(outDir.getAbsolutePath())

// The Appose Python service is started by the extension's GUI install path,
// which never runs headless. runForAnnotations happens to initialise it; the
// calibration runner assumes it is already up and fails with "Appose service
// is not available" after doing all the region extraction. Start it first.
import qupath.ext.fiberanalysis.service.ApposeFiberService
def svc = ApposeFiberService.getInstance()
if (!svc.isAvailable()) {
    println "APPOSE|initialising..."
    svc.initialize({ m -> println "APPOSE|${m}" })
}
println "APPOSE|available=${svc.isAvailable()} fiberlib=${svc.getInstalledFiberlibVersion()}"

// loadCalibratedThreshold resolves the project through the GUI singleton and
// then QP.getProject(). Headless there is no GUI, so without this the
// calibrated threshold is not found and the run silently falls back to
// per-region Otsu -- which is what it did for a whole 26-image project.
import qupath.lib.scripting.QP
QP.setBatchProjectAndImage(project, null)

def params = FiberAnalysisParams.fromPreferences()
println "CONFIG|calibration=${calName}|downsample=${ds}|windowUm=${winUm}"

int done = 0, failed = 0, skipped = 0
long tAll = System.currentTimeMillis()
for (entry in project.getImageList()) {
    def name = entry.getImageName()
    if (filter != null && !name.toLowerCase().contains(filter.toLowerCase())) continue
    def imageData
    try { imageData = entry.readImageData() }
    catch (Exception e) { println "FAIL|${name}|readImageData: ${e.getMessage()}"; failed++; continue }

    def anns = imageData.getHierarchy().getAnnotationObjects().findAll {
        it.getPathClass()?.toString() == "Acquired"
    }
    if (anns.isEmpty()) { println "SKIP|${name}|no Tissue annotation"; skipped++; continue }

    long t0 = System.currentTimeMillis()
    try {
        def before = outDir.listFiles({ f -> f.isDirectory() } as java.io.FileFilter)?.length ?: 0
        def thread = new FiberAnalysisWorkflow(null).runForAnnotations(params, anns, imageData, null)
        if (thread != null) thread.join()

        // The workflow catches per-annotation failures on its own worker
        // thread, so join() returning says nothing about success -- a run that
        // refused every annotation still reported OK here. Check the artifact.
        def runDirs = outDir.listFiles({ f -> f.isDirectory() } as java.io.FileFilter)
                            ?.sort { -it.lastModified() }
        def newest = runDirs ? runDirs[0] : null
        int produced = 0
        if (newest != null) {
            newest.eachDir { ad -> if (new File(ad, "results.json").exists()) produced++ }
        }
        if (produced < anns.size()) {
            println "FAIL|${name}|${produced}/${anns.size()} annotations produced results.json -- see the log"
            failed++
        } else {
            println "OK|${name}|${anns.size()}|${String.format('%.1f', (System.currentTimeMillis()-t0)/1000.0)}s"
            done++
        }
    } catch (Exception e) {
        println "FAIL|${name}|${e.getClass().getSimpleName()}: ${e.getMessage()}"
        failed++
    }
}
println "SUMMARY|done=${done}|failed=${failed}|skipped=${skipped}|totalMin=${String.format('%.1f', (System.currentTimeMillis()-tAll)/60000.0)}"
println "DONE"
QP.resetBatchProjectAndImage()
