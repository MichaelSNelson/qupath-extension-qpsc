package qupath.ext.qpsc.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.TestWorkFlowController;

import java.util.ResourceBundle;

/**
 * TestWorkflow
 *
 * Entry point for a test workflow that demonstrates a heartbeat connection
 * with a Python process. Launches the test dialog and starts the heartbeat server
 * and Python script only if the dialog is successfully opened and not cancelled.
 */
public class TestWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(TestWorkflow.class);

    /**
     * Entry point called by your "Test" menu item.
     */
    public static void runTestWorkflow() {
        var res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
        // Only show the dialog and start heartbeat if dialog successfully completes
        TestWorkFlowController.showDialog()
                .thenAccept(workflowResult -> {
                    // Dialog completed, workflow ran to completion.
                    logger.info("Test workflow finished cleanly.");
                })
                .exceptionally(ex -> {
                    // If dialog failed or was cancelled, do not launch heartbeat or Python
                    logger.error("Test workflow aborted or failed: {}", ex.getMessage());
                    return null;
                });
    }
}
