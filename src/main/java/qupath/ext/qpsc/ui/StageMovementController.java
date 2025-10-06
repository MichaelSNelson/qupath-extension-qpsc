package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.ResourceBundle;

/**
 * StageMovementController - Manual microscope stage control interface
 * 
 * <p>This controller provides a dialog interface for manual stage control, allowing users to move 
 * X/Y, Z, and R (polarizer) axes independently while enforcing configured safety limits. It 
 * validates movements against stage boundaries and provides real-time feedback on position updates.
 * 
 * <p>Key features:
 * <ul>
 *   <li>Independent control of X/Y stage positioning with bounds validation</li>
 *   <li>Z-axis focus control with configured limit enforcement</li>
 *   <li>Polarizer rotation (R-axis) control without bounds restrictions</li>
 *   <li>Real-time position feedback and status updates</li>
 *   <li>Safety validation against configured stage boundaries</li>
 *   <li>Error handling and user notification for invalid operations</li>
 * </ul>
 * 
 * <p>Dialog operation flow:
 * <ol>
 *   <li>Initialize dialog with current stage positions from hardware</li>
 *   <li>Load stage boundary configuration from MicroscopeConfigManager</li>
 *   <li>Present independent input fields for X, Y, Z, and R coordinates</li>
 *   <li>Validate input values against configured stage limits before movement</li>
 *   <li>Execute stage movements via MicroscopeController</li>
 *   <li>Update status labels with movement confirmation or error messages</li>
 * </ol>
 * 
 * <p>The dialog remains open for multiple movements, allowing users to perform sequential 
 * positioning operations without reopening the interface.
 * 
 * @author Mike Nelson
 * @since 1.0
 */
public class StageMovementController {

    private static final Logger logger = LoggerFactory.getLogger(StageMovementController.class);

    /**
     * Displays the stage movement control dialog for manual microscope positioning.
     * 
     * <p>This method creates and shows a non-modal dialog that allows users to control the microscope
     * stage position across X/Y, Z, and R (polarizer) axes. The dialog initializes with current
     * hardware positions and provides real-time movement controls with safety validation.
     * 
     * <p>Dialog components:
     * <ul>
     *   <li>Text fields for X, Y, Z, and R coordinate input</li>
     *   <li>Move buttons for each axis with bounds checking</li>
     *   <li>Status labels showing movement confirmations and errors</li>
     *   <li>Configuration-based boundary validation for X/Y and Z axes</li>
     * </ul>
     * 
     * <p>The dialog executes on the JavaFX Application Thread and remains open for multiple
     * movement operations. Stage boundaries are enforced through MicroscopeConfigManager
     * validation before any movement commands are sent to hardware.
     * 
     * @throws RuntimeException if resource bundle loading fails or dialog creation encounters errors
     */
    public static void showTestStageMovementDialog() {
        logger.info("Initiating stage movement dialog display");
        Platform.runLater(() -> {
            logger.debug("Creating stage movement dialog on JavaFX Application Thread");
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
            Dialog<Void> dlg = new Dialog<>();
            dlg.setTitle(res.getString("stageMovement.title"));
            dlg.setHeaderText(res.getString("stageMovement.header"));
            logger.debug("Dialog created with title: {}", res.getString("stageMovement.title"));

            // --- Fields and status labels ---
            logger.debug("Creating UI input fields and status labels");
            TextField xField = new TextField();
            TextField yField = new TextField();
            Label xyStatus = new Label();

            TextField zField = new TextField();
            Label zStatus = new Label();

            TextField rField = new TextField();
            Label rStatus = new Label();

            // --- Initialize from hardware ---
            logger.debug("Initializing dialog fields with current stage positions from hardware");
            try {
                double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                xField.setText(String.format("%.2f", xy[0]));
                yField.setText(String.format("%.2f", xy[1]));
                logger.debug("Initialized XY fields with current position: X={}, Y={}", xy[0], xy[1]);
            } catch (Exception e) {
                logger.warn("Failed to retrieve current XY stage position: {}", e.getMessage());
                MicroscopeErrorHandler.handleException(e, "get current XY stage position");
            }

            try {
                double z = MicroscopeController.getInstance().getStagePositionZ();
                zField.setText(String.format("%.2f", z));
                logger.debug("Initialized Z field with current position: {}", z);
            } catch (Exception e) {
                logger.warn("Failed to retrieve current Z stage position: {}", e.getMessage());
                // Don't show error again if XY already failed (same root cause)
            }

            try {
                double r = MicroscopeController.getInstance().getStagePositionR();
                rField.setText(String.format("%.2f", r));
                logger.debug("Initialized R field with current position: {}", r);
            } catch (Exception e) {
                logger.warn("Failed to retrieve current R stage position: {}", e.getMessage());
                // Don't show error again if XY already failed (same root cause)
            }

            // Get config manager for bounds checking
            logger.debug("Loading microscope configuration for stage bounds validation");
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            logger.debug("Configuration manager initialized with path: {}", configPath);

            // --- Buttons ---
            logger.debug("Creating movement control buttons");
            Button moveXYBtn = new Button(res.getString("stageMovement.button.moveXY"));
            Button moveZBtn  = new Button(res.getString("stageMovement.button.moveZ"));
            Button moveRBtn  = new Button(res.getString("stageMovement.button.moveR"));

            // --- Actions (dialog stays open) ---
            logger.debug("Setting up button action handlers for stage movements");
            moveXYBtn.setOnAction(e -> {
                logger.debug("XY movement button clicked");
                try {
                    double x = Double.parseDouble(xField.getText());
                    double y = Double.parseDouble(yField.getText());
                    logger.debug("Parsed XY coordinates for movement: X={}, Y={}", x, y);
                    
                    // *** BOUNDS CHECK using ConfigManager directly ***
                    if (!mgr.isWithinStageBounds(x, y)) {
                        logger.warn("XY movement rejected - coordinates out of bounds: X={}, Y={}", x, y);
                        UIFunctions.notifyUserOfError(
                                res.getString("stageMovement.error.outOfBoundsXY"),
                                res.getString("stageMovement.title"));
                        UIFunctions.showAlertDialog(res.getString("stageMovement.error.outOfBoundsXY"));
                        return;
                    }
                    
                    logger.info("Executing XY stage movement to position: X={}, Y={}", x, y);
                    MicroscopeController.getInstance().moveStageXY(x, y);
                    xyStatus.setText(
                            String.format(res.getString("stageMovement.status.xyMoved"), x, y));
                    logger.info("XY stage movement completed successfully: X={}, Y={}", x, y);
                } catch (NumberFormatException ex) {
                    logger.warn("Invalid XY coordinate format - X: '{}', Y: '{}'", xField.getText(), yField.getText());
                    UIFunctions.notifyUserOfError(
                            "Invalid coordinate format: " + ex.getMessage(), res.getString("stageMovement.title"));
                } catch (Exception ex) {
                    logger.error("XY stage movement failed: {}", ex.getMessage(), ex);
                    UIFunctions.notifyUserOfError(
                            ex.getMessage(), res.getString("stageMovement.title"));
                }
            });

            moveZBtn.setOnAction(e -> {
                logger.debug("Z movement button clicked");
                try {
                    double z = Double.parseDouble(zField.getText());
                    logger.debug("Parsed Z coordinate for movement: {}", z);
                    
                    // *** BOUNDS CHECK using ConfigManager directly ***
                    if (!mgr.isWithinStageBounds(z)) {
                        logger.warn("Z movement rejected - coordinate out of bounds: {}", z);
                        UIFunctions.notifyUserOfError(
                                res.getString("stageMovement.error.outOfBoundsZ"),
                                res.getString("stageMovement.title"));
                        UIFunctions.showAlertDialog(res.getString("stageMovement.error.outOfBoundsZ"));
                        return;
                    }
                    
                    logger.info("Executing Z stage movement to position: {}", z);
                    MicroscopeController.getInstance().moveStageZ(z);
                    zStatus.setText(
                            String.format(res.getString("stageMovement.status.zMoved"), z));
                    logger.info("Z stage movement completed successfully: {}", z);
                } catch (NumberFormatException ex) {
                    logger.warn("Invalid Z coordinate format: '{}'", zField.getText());
                    UIFunctions.notifyUserOfError(
                            "Invalid coordinate format: " + ex.getMessage(), res.getString("stageMovement.title"));
                } catch (Exception ex) {
                    logger.error("Z stage movement failed: {}", ex.getMessage(), ex);
                    UIFunctions.notifyUserOfError(
                            ex.getMessage(), res.getString("stageMovement.title"));
                }
            });

            moveRBtn.setOnAction(e -> {
                logger.debug("R movement button clicked");
                try {
                    double r = Double.parseDouble(rField.getText());
                    logger.debug("Parsed R coordinate for movement: {}", r);
                    
                    // no bounds for R (polarizer rotation is unrestricted)
                    logger.info("Executing R stage movement to position: {}", r);
                    MicroscopeController.getInstance().moveStageR(r);
                    rStatus.setText(
                            String.format(res.getString("stageMovement.status.rMoved"), r));
                    logger.info("R stage movement completed successfully: {}", r);
                } catch (NumberFormatException ex) {
                    logger.warn("Invalid R coordinate format: '{}'", rField.getText());
                    UIFunctions.notifyUserOfError(
                            "Invalid coordinate format: " + ex.getMessage(), res.getString("stageMovement.title"));
                } catch (Exception ex) {
                    logger.error("R stage movement failed: {}", ex.getMessage(), ex);
                    UIFunctions.notifyUserOfError(
                            ex.getMessage(), res.getString("stageMovement.title"));
                }
            });

            // --- Layout ---
            logger.debug("Creating and configuring dialog layout grid");
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(20));

            // XY
            logger.debug("Adding XY control components to dialog grid");
            grid.add(new Label(res.getString("stageMovement.label.x")), 0, 0);
            grid.add(xField, 1, 0);
            grid.add(new Label(res.getString("stageMovement.label.y")), 0, 1);
            grid.add(yField, 1, 1);
            grid.add(moveXYBtn, 2, 0, 1, 2);
            grid.add(xyStatus,    1, 2, 2, 1);

            // Z
            logger.debug("Adding Z control components to dialog grid");
            grid.add(new Label(res.getString("stageMovement.label.z")), 0, 3);
            grid.add(zField, 1, 3);
            grid.add(moveZBtn, 2, 3);
            grid.add(zStatus,  1, 4, 2, 1);

            // R
            logger.debug("Adding R control components to dialog grid");
            grid.add(new Label(res.getString("stageMovement.label.r")), 0, 5);
            grid.add(rField, 1, 5);
            grid.add(moveRBtn, 2, 5);
            grid.add(rStatus, 1, 6, 2, 1);

            logger.debug("Finalizing dialog configuration and displaying");
            dlg.getDialogPane().setContent(grid);
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dlg.initModality(Modality.NONE);
            dlg.show();
            logger.info("Stage movement dialog displayed successfully");
        });
    }
}