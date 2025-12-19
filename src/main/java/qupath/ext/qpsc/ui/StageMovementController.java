package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.stage.Stage;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
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

            // --- XY Arrow Controls ---
            logger.debug("Creating XY arrow navigation controls");
            TextField xyStepField = new TextField("100");
            xyStepField.setPrefWidth(70);
            xyStepField.setAlignment(Pos.CENTER);
            Tooltip.install(xyStepField, new Tooltip("Step size in micrometers"));

            Button upBtn = new Button("\u2191");  // Up arrow
            Button downBtn = new Button("\u2193");  // Down arrow
            Button leftBtn = new Button("\u2190");  // Left arrow
            Button rightBtn = new Button("\u2192");  // Right arrow

            // Style arrow buttons
            String arrowBtnStyle = "-fx-font-size: 14px; -fx-min-width: 30px; -fx-min-height: 30px;";
            upBtn.setStyle(arrowBtnStyle);
            downBtn.setStyle(arrowBtnStyle);
            leftBtn.setStyle(arrowBtnStyle);
            rightBtn.setStyle(arrowBtnStyle);

            // Helper to move XY by delta and update fields
            Runnable updateXYFieldsFromHardware = () -> {
                try {
                    double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                    xField.setText(String.format("%.2f", xy[0]));
                    yField.setText(String.format("%.2f", xy[1]));
                } catch (Exception ex) {
                    logger.warn("Failed to update XY fields from hardware: {}", ex.getMessage());
                }
            };

            upBtn.setOnAction(e -> {
                try {
                    double step = Double.parseDouble(xyStepField.getText());
                    double currentY = Double.parseDouble(yField.getText());
                    double newY = currentY + step;
                    double currentX = Double.parseDouble(xField.getText());

                    if (!mgr.isWithinStageBounds(currentX, newY)) {
                        xyStatus.setText("Y+ move out of bounds");
                        return;
                    }

                    MicroscopeController.getInstance().moveStageXY(currentX, newY);
                    yField.setText(String.format("%.2f", newY));
                    xyStatus.setText(String.format("Moved Y+ to %.2f", newY));
                    logger.debug("Arrow Y+ movement to: {}", newY);
                } catch (Exception ex) {
                    logger.warn("Arrow Y+ movement failed: {}", ex.getMessage());
                    xyStatus.setText("Move failed: " + ex.getMessage());
                }
            });

            downBtn.setOnAction(e -> {
                try {
                    double step = Double.parseDouble(xyStepField.getText());
                    double currentY = Double.parseDouble(yField.getText());
                    double newY = currentY - step;
                    double currentX = Double.parseDouble(xField.getText());

                    if (!mgr.isWithinStageBounds(currentX, newY)) {
                        xyStatus.setText("Y- move out of bounds");
                        return;
                    }

                    MicroscopeController.getInstance().moveStageXY(currentX, newY);
                    yField.setText(String.format("%.2f", newY));
                    xyStatus.setText(String.format("Moved Y- to %.2f", newY));
                    logger.debug("Arrow Y- movement to: {}", newY);
                } catch (Exception ex) {
                    logger.warn("Arrow Y- movement failed: {}", ex.getMessage());
                    xyStatus.setText("Move failed: " + ex.getMessage());
                }
            });

            leftBtn.setOnAction(e -> {
                try {
                    double step = Double.parseDouble(xyStepField.getText());
                    double currentX = Double.parseDouble(xField.getText());
                    double newX = currentX - step;
                    double currentY = Double.parseDouble(yField.getText());

                    if (!mgr.isWithinStageBounds(newX, currentY)) {
                        xyStatus.setText("X- move out of bounds");
                        return;
                    }

                    MicroscopeController.getInstance().moveStageXY(newX, currentY);
                    xField.setText(String.format("%.2f", newX));
                    xyStatus.setText(String.format("Moved X- to %.2f", newX));
                    logger.debug("Arrow X- movement to: {}", newX);
                } catch (Exception ex) {
                    logger.warn("Arrow X- movement failed: {}", ex.getMessage());
                    xyStatus.setText("Move failed: " + ex.getMessage());
                }
            });

            rightBtn.setOnAction(e -> {
                try {
                    double step = Double.parseDouble(xyStepField.getText());
                    double currentX = Double.parseDouble(xField.getText());
                    double newX = currentX + step;
                    double currentY = Double.parseDouble(yField.getText());

                    if (!mgr.isWithinStageBounds(newX, currentY)) {
                        xyStatus.setText("X+ move out of bounds");
                        return;
                    }

                    MicroscopeController.getInstance().moveStageXY(newX, currentY);
                    xField.setText(String.format("%.2f", newX));
                    xyStatus.setText(String.format("Moved X+ to %.2f", newX));
                    logger.debug("Arrow X+ movement to: {}", newX);
                } catch (Exception ex) {
                    logger.warn("Arrow X+ movement failed: {}", ex.getMessage());
                    xyStatus.setText("Move failed: " + ex.getMessage());
                }
            });

            // Arrange arrows in a cross pattern with step field in center
            GridPane arrowGrid = new GridPane();
            arrowGrid.setAlignment(Pos.CENTER);
            arrowGrid.setHgap(2);
            arrowGrid.setVgap(2);
            arrowGrid.add(upBtn, 1, 0);
            arrowGrid.add(leftBtn, 0, 1);
            arrowGrid.add(xyStepField, 1, 1);
            arrowGrid.add(rightBtn, 2, 1);
            arrowGrid.add(downBtn, 1, 2);

            Label arrowLabel = new Label("um");
            arrowLabel.setStyle("-fx-font-size: 10px;");
            arrowGrid.add(arrowLabel, 2, 2);

            // --- Z Scroll Control ---
            logger.debug("Adding Z scroll wheel control");
            TextField zStepField = new TextField("10");
            zStepField.setPrefWidth(50);
            zStepField.setAlignment(Pos.CENTER);
            Tooltip.install(zStepField, new Tooltip("Z step size (um) - use mouse wheel over Z controls"));

            Label zScrollLabel = new Label("Z step:");
            zScrollLabel.setStyle("-fx-font-size: 10px;");

            HBox zScrollBox = new HBox(5, zScrollLabel, zStepField, new Label("um"));
            zScrollBox.setAlignment(Pos.CENTER_LEFT);

            // Add scroll handler to Z field and button
            javafx.event.EventHandler<ScrollEvent> zScrollHandler = event -> {
                try {
                    double step = Double.parseDouble(zStepField.getText());
                    double currentZ = Double.parseDouble(zField.getText());

                    // Scroll up = positive delta = move Z up (increase)
                    // Scroll down = negative delta = move Z down (decrease)
                    double direction = event.getDeltaY() > 0 ? 1 : -1;
                    double newZ = currentZ + (step * direction);

                    if (!mgr.isWithinStageBounds(newZ)) {
                        zStatus.setText("Z move out of bounds");
                        event.consume();
                        return;
                    }

                    MicroscopeController.getInstance().moveStageZ(newZ);
                    zField.setText(String.format("%.2f", newZ));
                    zStatus.setText(String.format("Scrolled Z to %.2f", newZ));
                    logger.debug("Scroll Z movement to: {}", newZ);
                } catch (Exception ex) {
                    logger.warn("Scroll Z movement failed: {}", ex.getMessage());
                    zStatus.setText("Z scroll failed: " + ex.getMessage());
                }
                event.consume();
            };

            zField.setOnScroll(zScrollHandler);
            moveZBtn.setOnScroll(zScrollHandler);
            zStepField.setOnScroll(zScrollHandler);

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
            grid.add(xyStatus, 1, 2, 2, 1);

            // Z with scroll control
            logger.debug("Adding Z control components to dialog grid");
            grid.add(new Label(res.getString("stageMovement.label.z")), 0, 3);
            grid.add(zField, 1, 3);
            grid.add(moveZBtn, 2, 3);
            grid.add(zScrollBox, 3, 3);  // Z step control with scroll wheel hint
            grid.add(zStatus, 1, 4, 3, 1);

            // R
            logger.debug("Adding R control components to dialog grid");
            grid.add(new Label(res.getString("stageMovement.label.r")), 0, 5);
            grid.add(rField, 1, 5);
            grid.add(moveRBtn, 2, 5);
            grid.add(rStatus, 1, 6, 3, 1);

            // --- Go to object centroid button and arrow controls ---
            logger.debug("Adding 'Go to object centroid' button and XY arrow controls");
            Separator separator = new Separator();
            separator.setPadding(new Insets(10, 0, 10, 0));
            grid.add(separator, 0, 7, 4, 1);

            Button goToCentroidBtn = new Button("Go to Object Centroid");
            Label centroidStatus = new Label();

            // Check conditions for enabling the button
            QuPathGUI gui = QuPathGUI.getInstance();

            // Try to get alignment - first from MicroscopeController, then from slide-specific storage
            AffineTransform currentTransform = MicroscopeController.getInstance().getCurrentTransform();

            // If no active transform, try to load slide-specific alignment for current image
            if (currentTransform == null && gui != null && gui.getProject() != null && gui.getImageData() != null) {
                logger.debug("No active transform, checking for slide-specific alignment");
                try {
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();

                    // Use actual image file name - this is how alignments are saved
                    String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                    if (imageName != null && !imageName.isEmpty()) {
                        logger.debug("Looking for slide-specific alignment for image: {}", imageName);
                        AffineTransform slideTransform = AffineTransformManager.loadSlideAlignment(project, imageName);

                        if (slideTransform != null) {
                            currentTransform = slideTransform;
                            // Also set it in MicroscopeController for use in the action handler
                            MicroscopeController.getInstance().setCurrentTransform(slideTransform);
                            logger.info("Loaded slide-specific alignment for image: {}", imageName);
                        } else {
                            logger.debug("No slide-specific alignment found for image: {}", imageName);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load slide-specific alignment: {}", e.getMessage());
                }
            }

            // Initial state - check if we have an alignment
            boolean hasAlignment = currentTransform != null;

            // Create a container for available alignments list (shown when no alignment for current image)
            ListView<String> alignmentListView = new ListView<>();
            alignmentListView.setPrefHeight(100);
            alignmentListView.setPrefWidth(280);
            alignmentListView.setVisible(false);
            alignmentListView.setManaged(false);

            Label availableLabel = new Label("Images with alignments:");
            availableLabel.setVisible(false);
            availableLabel.setManaged(false);

            if (!hasAlignment) {
                goToCentroidBtn.setDisable(true);

                // Get list of available alignments
                String currentImageName = gui != null && gui.getImageData() != null
                        ? QPProjectFunctions.getActualImageFileName(gui.getImageData())
                        : "unknown";

                @SuppressWarnings("unchecked")
                Project<BufferedImage> projectForList = gui != null && gui.getProject() != null
                        ? (Project<BufferedImage>) gui.getProject()
                        : null;
                List<String> availableAlignments = getAvailableAlignments(projectForList);

                if (availableAlignments.isEmpty()) {
                    centroidStatus.setText("No alignments available in project");
                } else {
                    centroidStatus.setText("No alignment for: " + currentImageName);

                    // Show the list of available alignments
                    availableLabel.setVisible(true);
                    availableLabel.setManaged(true);
                    alignmentListView.setVisible(true);
                    alignmentListView.setManaged(true);
                    alignmentListView.getItems().addAll(availableAlignments);
                }

                logger.debug("Go to centroid button disabled - no alignment for current image. {} alignments available in project.",
                        availableAlignments.size());
            } else {
                logger.debug("Go to centroid button enabled - alignment available");
            }

            goToCentroidBtn.setOnAction(e -> {
                logger.debug("Go to object centroid button clicked");
                try {
                    // Re-check transform in case it was set after dialog opened
                    AffineTransform transform = MicroscopeController.getInstance().getCurrentTransform();

                    // If still no transform, try slide-specific alignment again
                    if (transform == null && gui != null && gui.getProject() != null && gui.getImageData() != null) {
                        try {
                            @SuppressWarnings("unchecked")
                            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                            if (imageName != null && !imageName.isEmpty()) {
                                transform = AffineTransformManager.loadSlideAlignment(project, imageName);
                                if (transform != null) {
                                    MicroscopeController.getInstance().setCurrentTransform(transform);
                                    logger.info("Loaded slide-specific alignment on button click for: {}", imageName);
                                }
                            }
                        } catch (Exception ex) {
                            logger.warn("Failed to load slide-specific alignment on click: {}", ex.getMessage());
                        }
                    }

                    if (transform == null) {
                        centroidStatus.setText("No alignment available");
                        UIFunctions.notifyUserOfError(
                                "No alignment is available. Please perform alignment first.",
                                "Go to Centroid");
                        return;
                    }

                    // Get selected object
                    if (gui == null || gui.getImageData() == null) {
                        centroidStatus.setText("No image open");
                        UIFunctions.notifyUserOfError(
                                "No image is currently open.",
                                "Go to Centroid");
                        return;
                    }

                    PathObject selectedObject = gui.getImageData().getHierarchy()
                            .getSelectionModel().getSelectedObject();

                    if (selectedObject == null) {
                        centroidStatus.setText("No object selected");
                        UIFunctions.notifyUserOfError(
                                "Please select an object in QuPath first.",
                                "Go to Centroid");
                        return;
                    }

                    if (selectedObject.getROI() == null) {
                        centroidStatus.setText("Selected object has no ROI");
                        UIFunctions.notifyUserOfError(
                                "The selected object has no region of interest.",
                                "Go to Centroid");
                        return;
                    }

                    // Get centroid coordinates from QuPath
                    double centroidX = selectedObject.getROI().getCentroidX();
                    double centroidY = selectedObject.getROI().getCentroidY();
                    logger.info("Object centroid in QuPath coordinates: ({}, {})", centroidX, centroidY);

                    // Transform to stage coordinates
                    double[] qpCoords = {centroidX, centroidY};
                    double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(
                            qpCoords, transform);
                    logger.info("Transformed to stage coordinates: ({}, {})", stageCoords[0], stageCoords[1]);

                    // Validate stage bounds
                    if (!mgr.isWithinStageBounds(stageCoords[0], stageCoords[1])) {
                        logger.warn("Centroid position out of stage bounds: ({}, {})", stageCoords[0], stageCoords[1]);
                        UIFunctions.notifyUserOfError(
                                "The object centroid position is outside the stage bounds.",
                                "Go to Centroid");
                        centroidStatus.setText("Position out of bounds");
                        return;
                    }

                    // Move stage to centroid position (XY only, no Z change)
                    logger.info("Moving stage to object centroid: ({}, {})", stageCoords[0], stageCoords[1]);
                    MicroscopeController.getInstance().moveStageXY(stageCoords[0], stageCoords[1]);

                    // Update status and XY fields to reflect new position
                    centroidStatus.setText(String.format("Moved to (%.2f, %.2f)", stageCoords[0], stageCoords[1]));
                    xField.setText(String.format("%.2f", stageCoords[0]));
                    yField.setText(String.format("%.2f", stageCoords[1]));
                    xyStatus.setText(String.format("Moved to object centroid (%.2f, %.2f)", stageCoords[0], stageCoords[1]));
                    logger.info("Successfully moved to object centroid");

                } catch (Exception ex) {
                    logger.error("Failed to move to object centroid: {}", ex.getMessage(), ex);
                    centroidStatus.setText("Error: " + ex.getMessage());
                    UIFunctions.notifyUserOfError(
                            "Failed to move to centroid: " + ex.getMessage(),
                            "Go to Centroid");
                }
            });

            grid.add(goToCentroidBtn, 0, 8, 2, 1);
            grid.add(arrowGrid, 2, 8, 2, 1);  // Arrow grid next to centroid button
            grid.add(centroidStatus, 0, 9, 4, 1);
            grid.add(availableLabel, 0, 10, 4, 1);
            grid.add(alignmentListView, 0, 11, 4, 1);

            logger.debug("Finalizing dialog configuration and displaying");
            dlg.getDialogPane().setContent(grid);
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dlg.initModality(Modality.NONE);

            // Make dialog always on top but not blocking
            dlg.setOnShown(event -> {
                Stage stage = (Stage) dlg.getDialogPane().getScene().getWindow();
                stage.setAlwaysOnTop(true);
                logger.debug("Stage movement dialog set to always on top");
            });

            dlg.show();
            logger.info("Stage movement dialog displayed successfully");
        });
    }

    /**
     * Gets a list of image names that have saved alignments in the project.
     *
     * @param project The QuPath project
     * @return List of image names with alignments, or empty list if none found
     */
    private static List<String> getAvailableAlignments(Project<BufferedImage> project) {
        List<String> alignedImages = new ArrayList<>();

        if (project == null || project.getPath() == null) {
            return alignedImages;
        }

        try {
            File projectDir = project.getPath().toFile().getParentFile();
            File alignmentDir = new File(projectDir, "alignmentFiles");

            if (!alignmentDir.exists() || !alignmentDir.isDirectory()) {
                return alignedImages;
            }

            File[] alignmentFiles = alignmentDir.listFiles((dir, name) -> name.endsWith("_alignment.json"));

            if (alignmentFiles != null) {
                for (File file : alignmentFiles) {
                    // Extract image name from filename (remove _alignment.json suffix)
                    String fileName = file.getName();
                    String imageName = fileName.substring(0, fileName.length() - "_alignment.json".length());
                    alignedImages.add(imageName);
                }
            }

            logger.debug("Found {} images with alignments in project", alignedImages.size());
        } catch (Exception e) {
            logger.warn("Error listing available alignments: {}", e.getMessage());
        }

        return alignedImages;
    }
}