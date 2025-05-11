package qupath.ext.qpsc.controller;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import javafx.concurrent.Task;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.ui.InterfaceController;

/**
 * QPScopeController
 *
 * <p>“Orchestrator” for your QuPath‐side workflow:
 *   - Presents dialogs and gathers user input (bounding box, sample name, etc.).
 *   - Drives the imaging pipeline sequence (tiling → acquisition → stitching).
 *   - Delegates low‐level calls to MicroscopeController and UtilityFunctions.
 *   - Keeps the UI responsive, showing progress and handling cancellations.
 */
public class QPScopeController {

    private static final Logger logger = Logger.getLogger(QPScopeController.class.getName());
    private static QPScopeController instance;

    // Private constructor for singleton pattern.
    private QPScopeController() {
        // Initialize any required state here.
    }

    public static synchronized QPScopeController getInstance() {
        if (instance == null) {
            instance = new QPScopeController();
        }
        return instance;
    }

    // This method can be called during extension initialization (if needed).
    public void init() {
        logger.info("QPScopeController: Starting initialization of interactive flow");
        // Kick off the first step of the flow if desired.
        startUserInteraction();
    }

    // Existing method for initiating the user interaction flow.
    private void startUserInteraction() {
        CompletableFuture<Void> userInputFuture = showUserDialog("Initial settings", "Please confirm settings.");
        userInputFuture.thenRunAsync(() -> {

        });
    }

    private CompletableFuture<Void> showUserDialog(String title, String message) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        logger.info("Showing dialog: " + title + " - " + message);
        new Thread(() -> {
            try {
                Thread.sleep(1000); // Simulate a delay for user confirmation.
            } catch (InterruptedException e) {
                // Handle interruption appropriately.
            }
            future.complete(null);
        }).start();
        return future;
    }

    /** Route the menu-selection to the right workflow */
    public void startWorkflow(String mode) throws IOException {
        switch (mode) {
            case "boundingBox"  -> MicroscopeController.getInstance().startBoundingBoxWorkflow();
            case "existingImage"-> MicroscopeController.getInstance().startExistingImageWorkflow();
            case "basicStageInterface"-> InterfaceController.showTestStageMovementDialog();
            case "test" ->  TestWorkflow.runTestWorkflow();
            default             -> logger.warning("Unknown workflow mode " + mode);
        }
    }


    private void completeInteraction() {
        logger.info("QPScopeController: Interaction complete.");
    }

}
