package qupath.ext.qpsc.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.UIFunctions;

public class TestWorkflow {


    private static final Logger logger =
            LoggerFactory.getLogger(TestWorkflow.class);
    /**
     * Dummy "Test Entry" workflow.
     * <ul>
     *   <li>Shows a dialog that asks the user for two coordinates (comma-separated).
     *   <li>Duplicates <code>project-structure.txt</code> in the extension folder,
     *       writing <code>project-structure2.txt</code> (and …3, …4 on subsequent runs).
     * </ul>
     *
     * @return the coordinates the user entered as [x, y], or null if the user cancelled or on error
     * @throws IOException if file I/O unexpectedly fails
     */
    public static void runTestWorkflow() {
        UIFunctions.showSampleSetupDialog()
                .thenCompose(sample -> showBoundingBoxDialog())
                .thenAccept(bb -> {
                    // finally build & fire your CLI call
                })
                .exceptionally(ex -> {  });
    }
}
