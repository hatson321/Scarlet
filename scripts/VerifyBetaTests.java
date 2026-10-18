import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/** Run the offline regression suite against a packaged JAR, not target/classes. */
public final class VerifyBetaTests {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getProperty("java.awt.headless"))
                || System.getProperty("SCARLET_HOME") == null)
            throw new IllegalArgumentException("Set headless=true and an isolated SCARLET_HOME test directory");
        Class<?> app = Class.forName("net.sybyline.scarlet.Scarlet", false,
            Thread.currentThread().getContextClassLoader());
        String location = app.getProtectionDomain().getCodeSource().getLocation().toString();
        if (!location.endsWith(".jar"))
            throw new IllegalStateException("Application classes are not loaded from a packaged JAR: " + location);
        System.out.println("Testing actual packaged application: " + location);
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("net.sybyline.scarlet")).build(), listener);
        TestExecutionSummary summary = listener.getSummary();
        PrintWriter output = new PrintWriter(System.out, true);
        summary.printTo(output);
        summary.printFailuresTo(output);
        if (summary.getTestsFoundCount() == 0 || summary.getTestsFailedCount() != 0
                || summary.getContainersFailedCount() != 0 || summary.getTestsAbortedCount() != 0
                || summary.getTestsSkippedCount() != 0)
            System.exit(1);
    }
}
