package qupath.ext.qpsc.ui.stagemap;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import javafx.beans.value.ChangeListener;
import java.awt.Desktop;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Floating window displaying a visual map of the microscope stage.
 * <p>
 * Features:
 * <ul>
 *   <li>Non-modal window that stays above QuPath main window but allows dialogs on top</li>
 *   <li>Real-time crosshair showing current objective position</li>
 *   <li>Camera FOV rectangle display</li>
 *   <li>Switchable insert configurations (single slide, multi-slide)</li>
 *   <li>Double-click navigation with safety confirmation</li>
 * </ul>
 * <p>
 * Uses a singleton pattern to ensure only one window instance exists.
 * The window is owned by the QuPath main window, ensuring it stays visible above
 * the main window while allowing modal dialogs to appear on top when needed.
 */
public class StageMapWindow {

    private static final Logger logger = LoggerFactory.getLogger(StageMapWindow.class);

    // ========== Singleton ==========
    private static StageMapWindow instance;

    // ========== Window Components ==========
    private Stage stage;
    private StageMapCanvas canvas;
    private ComboBox<StageInsert> insertComboBox;
    private Label positionLabel;
    private Label targetLabel;
    private Label statusLabel;

    // ========== State ==========
    private ScheduledExecutorService positionPoller;
    private volatile boolean isPolling = false;
    private volatile boolean dialogShowing = false;  // Pause updates while dialogs are shown
    private volatile int consecutiveErrors = 0;  // Track polling failures
    private static final int MAX_CONSECUTIVE_ERRORS = 10;  // Pause polling after this many errors
    private static boolean movementWarningShownThisSession = false;

    // ========== Macro Overlay State ==========
    private CheckBox macroOverlayCheckbox;
    private BufferedImage currentMacroImage = null;
    private AffineTransform currentMacroTransform = null;
    private String currentMacroSampleName = null;
    private ChangeListener<ImageData<?>> imageChangeListener = null;

    // ========== Configuration ==========
    // Poll interval for position updates - lower = more responsive but more network traffic
    // 200ms provides smooth tracking without overwhelming the socket connection
    private static final long POLL_INTERVAL_MS = 200;
    private static final double WINDOW_WIDTH = 420;
    private static final double WINDOW_HEIGHT = 380;
    private static final double CANVAS_WIDTH = 380;
    private static final double CANVAS_HEIGHT = 280;

    private StageMapWindow() {
        buildUI();
        loadInsertConfigurations();
    }

    /**
     * Shows the Stage Map window, creating it if necessary.
     * If already visible, brings it to front.
     */
    public static void show() {
        Platform.runLater(() -> {
            if (instance == null) {
                instance = new StageMapWindow();
            }

            if (!instance.stage.isShowing()) {
                instance.stage.show();
                instance.startPositionPolling();
            } else {
                instance.stage.toFront();
            }
        });
    }

    /**
     * Hides the Stage Map window.
     */
    public static void hide() {
        Platform.runLater(() -> {
            if (instance != null) {
                instance.dispose();
            }
        });
    }

    /**
     * Checks if the Stage Map window is currently visible.
     */
    public static boolean isVisible() {
        return instance != null && instance.stage != null && instance.stage.isShowing();
    }

    /**
     * Disposes of the window and resets the singleton for a clean re-open.
     * This prevents stale state issues when reopening after the microscope
     * disconnects or Live Mode is toggled.
     */
    private void dispose() {
        logger.debug("Disposing Stage Map window");

        // Unregister image change listener first
        unregisterImageChangeListener();

        // Stop polling
        stopPositionPolling();

        // Disable canvas rendering to prevent further texture operations
        if (canvas != null) {
            canvas.setRenderingEnabled(false);
        }

        // Hide and close the stage
        if (stage != null) {
            stage.hide();
            stage.close();
        }

        // Reset singleton so next show() creates a fresh instance
        instance = null;

        logger.info("Stage Map window disposed - will create fresh instance on next open");
    }

    private void buildUI() {
        stage = new Stage();
        stage.setTitle("Stage Map");
        stage.initModality(Modality.NONE);

        // Set owner to QuPath main window - this keeps Stage Map above the main window
        // but allows modal dialogs to appear on top (unlike setAlwaysOnTop which blocks everything)
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            stage.initOwner(gui.getStage());
            logger.debug("Stage Map window owner set to QuPath main window");
        } else {
            // Fallback to alwaysOnTop if QuPath stage not available (shouldn't normally happen)
            stage.setAlwaysOnTop(true);
            logger.warn("Could not get QuPath main stage, using alwaysOnTop as fallback");
        }

        stage.setResizable(true);
        stage.setMinWidth(350);
        stage.setMinHeight(320);

        // Main layout
        VBox root = new VBox(8);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #2b2b2b;");

        // Top controls: Insert selector
        HBox topBar = buildTopBar();

        // Canvas for stage visualization (new WritableImage + Shapes implementation)
        canvas = new StageMapCanvas(CANVAS_WIDTH, CANVAS_HEIGHT);
        canvas.setClickHandler(this::handleCanvasClick);

        // The canvas is now a StackPane that resizes with its container
        // Just place it in a container that grows with the window
        StackPane canvasContainer = new StackPane(canvas);
        canvasContainer.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #555; -fx-border-width: 1;");
        VBox.setVgrow(canvasContainer, Priority.ALWAYS);

        // Notify canvas when container size changes
        canvasContainer.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (Math.abs(newVal.doubleValue() - oldVal.doubleValue()) > 1) {
                canvas.onSizeChanged();
            }
        });
        canvasContainer.heightProperty().addListener((obs, oldVal, newVal) -> {
            if (Math.abs(newVal.doubleValue() - oldVal.doubleValue()) > 1) {
                canvas.onSizeChanged();
            }
        });

        // Bottom status bar
        HBox bottomBar = buildBottomBar();

        root.getChildren().addAll(topBar, canvasContainer, bottomBar);

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add(getClass().getResource("/qupath/ext/qpsc/ui/stagemap/stagemap.css") != null
                ? getClass().getResource("/qupath/ext/qpsc/ui/stagemap/stagemap.css").toExternalForm()
                : "");

        stage.setScene(scene);

        // Stop polling when window is closed
        stage.setOnCloseRequest(e -> dispose());

        // Register image change listener for macro overlay availability
        registerImageChangeListener();
    }

    private HBox buildTopBar() {
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label insertLabel = new Label("Insert:");
        insertLabel.setStyle("-fx-text-fill: #ccc;");

        insertComboBox = new ComboBox<>();
        insertComboBox.setPrefWidth(200);
        insertComboBox.setOnAction(e -> {
            StageInsert selected = insertComboBox.getValue();
            if (selected != null) {
                canvas.setInsert(selected);
                logger.debug("Switched to insert configuration: {}", selected.getId());
            }
        });

        // Button to open config folder for calibration
        Button configButton = new Button("Config");
        configButton.setStyle("-fx-font-size: 10; -fx-padding: 2 6;");
        configButton.setTooltip(new Tooltip(
                "Open the configuration folder to edit calibration values.\n" +
                "Edit the YAML file to set aperture and slide reference points."));
        configButton.setOnAction(e -> openConfigFolder());

        // Tooltip explaining the interface
        Button helpButton = new Button("?");
        helpButton.setStyle("-fx-font-size: 10; -fx-padding: 2 6;");
        helpButton.setTooltip(new Tooltip(
                "Stage Map shows the microscope stage position.\n\n" +
                "- Green crosshair: Current objective position\n" +
                "- Orange rectangle: Camera field of view\n" +
                "- Blue rectangles: Slide positions\n" +
                "- Green zones: Safe movement areas\n" +
                "- Red zones: Off-slide areas\n\n" +
                "Double-click to move the stage to that position.\n" +
                "Select insert type to change slide layout.\n\n" +
                "CALIBRATION: Use Stage Control to find:\n" +
                "- Left/right aperture edges (X coords)\n" +
                "- Top/bottom slide edges (Y coords)\n" +
                "Then edit the config YAML file."));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Macro overlay checkbox
        macroOverlayCheckbox = new CheckBox("Overlay Macro");
        macroOverlayCheckbox.setStyle("-fx-text-fill: #ccc;");
        macroOverlayCheckbox.setTooltip(new Tooltip(
                "Display the cropped macro image from alignment\n" +
                "over the stage map at its calibrated position.\n\n" +
                "This shows the slide-only portion of the macro\n" +
                "(with slide holder and background removed)\n" +
                "that was saved during Microscope Alignment.\n\n" +
                "Only available for images with saved alignments."));
        macroOverlayCheckbox.setDisable(true);  // Initially disabled until macro is available
        macroOverlayCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (canvas != null) {
                if (newVal && currentMacroImage != null && currentMacroTransform != null) {
                    canvas.setMacroOverlay(currentMacroImage, currentMacroTransform);
                } else {
                    canvas.clearMacroOverlay();
                }
            }
        });

        topBar.getChildren().addAll(insertLabel, insertComboBox, spacer, macroOverlayCheckbox, configButton, helpButton);
        return topBar;
    }

    /**
     * Opens the folder containing the microscope configuration file.
     */
    private void openConfigFolder() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isEmpty()) {
                showWarning("No Config File",
                        "No microscope configuration file is set.\n" +
                        "Please set one in QuPath preferences.");
                return;
            }

            File configFile = new File(configPath);
            File configFolder = configFile.getParentFile();

            if (configFolder == null || !configFolder.exists()) {
                showWarning("Folder Not Found",
                        "Configuration folder not found:\n" + configPath);
                return;
            }

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(configFolder);
                logger.info("Opened config folder: {}", configFolder.getAbsolutePath());
            } else {
                // Fallback: show the path in a dialog
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Configuration Folder");
                alert.setHeaderText("Open this folder to edit calibration:");
                alert.setContentText(configFolder.getAbsolutePath());
                alert.initOwner(stage);
                alert.showAndWait();
            }

        } catch (Exception e) {
            logger.error("Failed to open config folder: {}", e.getMessage(), e);
            showError("Error", "Failed to open configuration folder:\n" + e.getMessage());
        }
    }

    private HBox buildBottomBar() {
        HBox bottomBar = new HBox(15);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(5, 0, 0, 0));

        positionLabel = new Label("Position: -- , --");
        positionLabel.setStyle("-fx-text-fill: #aaa; -fx-font-family: monospace;");

        targetLabel = new Label("");
        targetLabel.setStyle("-fx-text-fill: #7ab; -fx-font-family: monospace;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #888;");

        bottomBar.getChildren().addAll(positionLabel, targetLabel, spacer, statusLabel);
        return bottomBar;
    }

    private void loadInsertConfigurations() {
        try {
            // Load configurations from YAML
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty());

            if (configManager != null) {
                StageInsertRegistry.loadFromConfig(configManager);
            }

            List<StageInsert> inserts = StageInsertRegistry.getAvailableInserts();
            insertComboBox.setItems(FXCollections.observableArrayList(inserts));

            // Select default
            StageInsert defaultInsert = StageInsertRegistry.getDefaultInsert();
            if (defaultInsert != null) {
                insertComboBox.setValue(defaultInsert);
                canvas.setInsert(defaultInsert);
            }

            logger.info("Loaded {} insert configurations", inserts.size());

        } catch (Exception e) {
            logger.error("Error loading insert configurations: {}", e.getMessage(), e);
            statusLabel.setText("Config error");
            statusLabel.setStyle("-fx-text-fill: #f66;");
        }
    }

    private void startPositionPolling() {
        if (isPolling) {
            return;
        }

        // Use a daemon thread so it won't prevent JVM shutdown
        positionPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StageMap-PositionPoller");
            t.setDaemon(true);
            return t;
        });
        positionPoller.scheduleAtFixedRate(
                this::pollPosition,
                0,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        isPolling = true;

        logger.debug("Started position polling ({}ms interval)", POLL_INTERVAL_MS);
    }

    private void stopPositionPolling() {
        if (positionPoller != null && !positionPoller.isShutdown()) {
            positionPoller.shutdownNow();
            isPolling = false;
            logger.debug("Stopped position polling");
        }
    }

    private void pollPosition() {
        // Skip updates if window is not visible or dialogs are showing
        if (dialogShowing || stage == null || !stage.isShowing()) {
            return;
        }

        // Skip if too many consecutive errors (microscope likely disconnected)
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            return;
        }

        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            double[] pos = controller.getStagePositionXY();

            if (pos != null && pos.length >= 2) {
                // Reset error counter on success
                consecutiveErrors = 0;

                Platform.runLater(() -> {
                    // Double-check state on FX thread
                    if (dialogShowing || stage == null || !stage.isShowing()) {
                        return;
                    }
                    canvas.updatePosition(pos[0], pos[1]);
                    positionLabel.setText(String.format("Pos: %.1f, %.1f um", pos[0], pos[1]));
                    statusLabel.setText("");
                    statusLabel.setStyle("-fx-text-fill: #888;");

                    // Update target label based on mouse position
                    double[] target = canvas.getTargetPosition();
                    if (target != null) {
                        StageInsert insert = insertComboBox.getValue();
                        boolean isLegal = insert != null && insert.isPositionLegal(target[0], target[1]);
                        targetLabel.setText(String.format("Target: %.1f, %.1f", target[0], target[1]));
                        targetLabel.setStyle(isLegal ? "-fx-text-fill: #7ab;" : "-fx-text-fill: #fa7;");
                    } else {
                        targetLabel.setText("");
                    }
                });

                // Update FOV if available
                if (!dialogShowing && stage != null && stage.isShowing()) {
                    updateFOV();
                }
            } else {
                // null position is also an error
                handlePollingError();
            }

        } catch (Exception e) {
            handlePollingError();
        }
    }

    private void handlePollingError() {
        consecutiveErrors++;

        // Only update UI once when we hit the error threshold
        if (consecutiveErrors == MAX_CONSECUTIVE_ERRORS) {
            logger.warn("Stage Map polling paused after {} consecutive errors - microscope may be disconnected",
                    MAX_CONSECUTIVE_ERRORS);
            Platform.runLater(() -> {
                if (stage != null && stage.isShowing() && !dialogShowing) {
                    // CRITICAL: Hide the canvas to stop JavaFX's internal rendering loop
                    // from trying to repaint a corrupted texture (causes NPE spam)
                    if (canvas != null) {
                        canvas.setVisible(false);
                    }
                    statusLabel.setText("Disconnected - reopen to reconnect");
                    statusLabel.setStyle("-fx-text-fill: #f66;");
                }
            });
        }
    }

    /**
     * Resets the error counter and resumes polling.
     * Call this when reconnecting to the microscope.
     */
    public void resetPollingErrors() {
        consecutiveErrors = 0;
        logger.info("Stage Map polling errors reset");
    }

    private void updateFOV() {
        try {
            MicroscopeConfigManager config = MicroscopeConfigManager.getInstance(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty());

            if (config == null) return;

            // Get current modality, objective, and detector to calculate FOV
            String modality = config.getString("microscope", "modality");
            String objective = config.getString("microscope", "objective_in_use");
            String detector = config.getString("microscope", "detector_in_use");

            if (modality != null && objective != null && detector != null) {
                double[] fov = config.getModalityFOV(modality, objective, detector);
                if (fov != null && fov.length >= 2) {
                    Platform.runLater(() -> canvas.updateFOV(fov[0], fov[1]));
                }
            }
        } catch (Exception e) {
            // FOV display is optional, don't log errors
        }
    }

    private void handleCanvasClick(double stageX, double stageY) {
        StageInsert insert = insertComboBox.getValue();
        if (insert == null) {
            logger.warn("No insert selected for stage movement");
            return;
        }

        // Check if position is within the aperture/insert bounds
        // Note: The aperture calibration points define the valid clickable area.
        // The stage.limits values in config are NOT accurate hardware limits.
        if (!insert.isPositionInInsert(stageX, stageY)) {
            logger.warn("Invalid position clicked: ({}, {}) - outside aperture for insert '{}'",
                    String.format("%.1f", stageX), String.format("%.1f", stageY), insert.getId());
            showWarning("Invalid Position",
                    "The selected position is outside the visible aperture.\n" +
                    "Please select a position within the stage insert area.");
            return;
        }

        // First movement warning
        if (!movementWarningShownThisSession) {
            boolean confirmed = showFirstMovementWarning(stageX, stageY);
            if (!confirmed) {
                return;
            }
            movementWarningShownThisSession = true;
        }

        // Execute the move
        executeMove(stageX, stageY);
    }

    private boolean showFirstMovementWarning(double targetX, double targetY) {
        dialogShowing = true;
        try {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Confirm Stage Movement");
            alert.setHeaderText("First Stage Movement This Session");
            alert.setContentText(String.format(
                    "You are about to move the microscope stage.\n\n" +
                    "Target position: (%.1f, %.1f) um\n\n" +
                    "IMPORTANT: Before moving, ensure:\n" +
                    "- The objective turret has adequate clearance\n" +
                    "- Lower-power objectives won't collide with the sample\n" +
                    "- The slide is properly secured in the insert\n\n" +
                    "This warning will not appear again this session.",
                    targetX, targetY));

            alert.initOwner(stage);

            ButtonType moveButton = new ButtonType("Move Stage", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(moveButton, cancelButton);

            Optional<ButtonType> result = alert.showAndWait();
            return result.isPresent() && result.get() == moveButton;
        } finally {
            dialogShowing = false;
        }
    }

    private void executeMove(double targetX, double targetY) {
        try {
            logger.info("Moving stage to ({}, {})", targetX, targetY);
            statusLabel.setText("Moving...");
            statusLabel.setStyle("-fx-text-fill: #fa7;");

            MicroscopeController controller = MicroscopeController.getInstance();
            controller.moveStageXY(targetX, targetY);

            // Position will update on next poll cycle

        } catch (Exception e) {
            logger.error("Failed to move stage: {}", e.getMessage(), e);
            showError("Movement Failed",
                    "Failed to move stage: " + e.getMessage());
            statusLabel.setText("Move failed");
            statusLabel.setStyle("-fx-text-fill: #f66;");
        }
    }

    private void showWarning(String title, String message) {
        Platform.runLater(() -> {
            dialogShowing = true;
            try {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle(title);
                alert.setHeaderText(null);
                alert.setContentText(message);
                alert.initOwner(stage);
                alert.showAndWait();
            } finally {
                dialogShowing = false;
            }
        });
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            dialogShowing = true;
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle(title);
                alert.setHeaderText(null);
                alert.setContentText(message);
                alert.initOwner(stage);
                alert.showAndWait();
            } finally {
                dialogShowing = false;
            }
        });
    }

    /**
     * Resets the session warning flag. Primarily for testing.
     */
    public static void resetWarningFlag() {
        movementWarningShownThisSession = false;
    }

    // ========== Macro Overlay Methods ==========

    /**
     * Registers a listener for QuPath image changes to update macro overlay availability.
     */
    private void registerImageChangeListener() {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null) {
            imageChangeListener = (obs, oldImage, newImage) -> {
                logger.debug("Image changed in QuPath, checking macro overlay availability");
                checkMacroOverlayAvailability();
            };
            gui.imageDataProperty().addListener(imageChangeListener);

            // Initial check
            checkMacroOverlayAvailability();
        }
    }

    /**
     * Unregisters the image change listener to prevent memory leaks.
     */
    private void unregisterImageChangeListener() {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && imageChangeListener != null) {
            gui.imageDataProperty().removeListener(imageChangeListener);
            imageChangeListener = null;
            logger.debug("Unregistered image change listener");
        }
    }

    /**
     * Checks if a macro overlay is available for the current QuPath image
     * and updates the checkbox state accordingly.
     */
    private void checkMacroOverlayAvailability() {
        Platform.runLater(() -> {
            currentMacroImage = null;
            currentMacroTransform = null;
            currentMacroSampleName = null;

            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getProject() == null || gui.getImageData() == null) {
                macroOverlayCheckbox.setDisable(true);
                macroOverlayCheckbox.setSelected(false);
                if (canvas != null) canvas.clearMacroOverlay();
                return;
            }

            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();

            // Get sample name for lookup
            String sampleName = getSampleNameForCurrentImage(gui, project);

            if (sampleName == null || sampleName.isEmpty()) {
                macroOverlayCheckbox.setDisable(true);
                macroOverlayCheckbox.setSelected(false);
                if (canvas != null) canvas.clearMacroOverlay();
                logger.debug("No sample name found for current image");
                return;
            }

            // Try to load macro image and transform
            BufferedImage macroImage = AffineTransformManager.loadSavedMacroImage(project, sampleName);
            AffineTransform transform = AffineTransformManager.loadSlideAlignment(project, sampleName);

            if (macroImage != null && transform != null) {
                currentMacroImage = macroImage;
                currentMacroTransform = transform;
                currentMacroSampleName = sampleName;
                macroOverlayCheckbox.setDisable(false);
                logger.info("Macro overlay available for sample: {}", sampleName);

                // If checkbox is already selected, update the overlay
                if (macroOverlayCheckbox.isSelected()) {
                    canvas.setMacroOverlay(currentMacroImage, currentMacroTransform);
                }
            } else {
                macroOverlayCheckbox.setDisable(true);
                macroOverlayCheckbox.setSelected(false);
                if (canvas != null) canvas.clearMacroOverlay();
                logger.debug("No macro overlay available for sample: {}", sampleName);
            }
        });
    }

    /**
     * Gets the sample name to use for macro lookup.
     * Tries multiple sources: image file name, sample_name metadata, base_image metadata,
     * and follows original_image_id for flipped images.
     *
     * @param gui The QuPath GUI instance
     * @param project The current project
     * @return The sample name, or null if not found
     */
    private String getSampleNameForCurrentImage(QuPathGUI gui, Project<BufferedImage> project) {
        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null) {
            return null;
        }

        // First try: actual image file name (how alignments are typically saved)
        String imageName = QPProjectFunctions.getActualImageFileName(imageData);
        if (imageName != null && !imageName.isEmpty()) {
            // Check if alignment exists for this name
            if (AffineTransformManager.loadSlideAlignment(project, imageName) != null) {
                logger.debug("Found alignment using image name: {}", imageName);
                return imageName;
            }
        }

        // Second try: get from project entry metadata
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
        if (entry != null) {
            // Try sample_name metadata
            String sampleName = ImageMetadataManager.getSampleName(entry);
            if (sampleName != null && !sampleName.isEmpty()) {
                if (AffineTransformManager.loadSlideAlignment(project, sampleName) != null) {
                    logger.debug("Found alignment using sample_name metadata: {}", sampleName);
                    return sampleName;
                }
            }

            // Try base_image metadata
            String baseImage = ImageMetadataManager.getBaseImage(entry);
            if (baseImage != null && !baseImage.isEmpty()) {
                if (AffineTransformManager.loadSlideAlignment(project, baseImage) != null) {
                    logger.debug("Found alignment using base_image metadata: {}", baseImage);
                    return baseImage;
                }
            }

            // For flipped images, try original's base_image
            if (ImageMetadataManager.isFlipped(entry)) {
                String originalId = ImageMetadataManager.getOriginalImageId(entry);
                if (originalId != null) {
                    for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
                        if (originalId.equals(e.getID())) {
                            String origBase = ImageMetadataManager.getBaseImage(e);
                            if (origBase != null && !origBase.isEmpty()) {
                                if (AffineTransformManager.loadSlideAlignment(project, origBase) != null) {
                                    logger.debug("Found alignment using original image's base_image: {}", origBase);
                                    return origBase;
                                }
                            }
                            break;
                        }
                    }
                }
            }
        }

        // Return image name even if no alignment found (for logging purposes)
        return imageName;
    }
}
