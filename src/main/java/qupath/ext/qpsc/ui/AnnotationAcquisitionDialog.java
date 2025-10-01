package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.scripting.QP;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Combined dialog for annotation validation and class selection.
 * First tab shows annotations to acquire, second tab allows class filtering.
 */
public class AnnotationAcquisitionDialog {
    private static final Logger logger = LoggerFactory.getLogger(AnnotationAcquisitionDialog.class);

    private static final String[] DEFAULT_CLASSES = {"Tissue", "Scanned Area", "Bounding Box"};

    /**
     * Shows the combined acquisition dialog.
     *
     * @param availableClasses Initial set of annotation classes in the current image
     * @param preselectedClasses Classes selected from previous runs
     * @param modality The imaging modality for modality-specific UI components
     * @return CompletableFuture with selected classes and whether to proceed
     */
    public static CompletableFuture<AcquisitionResult> showDialog(
            Set<String> availableClasses,
            List<String> preselectedClasses,
            String modality) {

        CompletableFuture<AcquisitionResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            // Get modality handler and optional UI component
            ModalityHandler handler = ModalityRegistry.getHandler(modality);
            Optional<ModalityHandler.BoundingBoxUI> modalityUI = handler.createBoundingBoxUI();
            Dialog<AcquisitionResult> dialog = new Dialog<>();
            dialog.initModality(Modality.NONE);
            dialog.setTitle("Annotation Acquisition");
            dialog.setHeaderText(null);

            // Set always-on-top to keep dialog visible during annotation editing
            dialog.getDialogPane().sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null && newScene.getWindow() instanceof javafx.stage.Stage stage) {
                    stage.setAlwaysOnTop(true);
                }
            });

            // Create observable list for selected classes
            // If no preselected classes, default to selecting any available default classes that have annotations
            List<String> initialSelection = new ArrayList<>(preselectedClasses);
            if (initialSelection.isEmpty()) {
                for (String defaultClass : DEFAULT_CLASSES) {
                    if (availableClasses.contains(defaultClass)) {
                        initialSelection.add(defaultClass);
                        logger.info("Auto-selecting default class with annotations: {}", defaultClass);
                    }
                }
            }
            ObservableList<String> selectedClasses = FXCollections.observableArrayList(initialSelection);

            // Create observable set for all available classes (will be updated dynamically)
            ObservableList<String> allAvailableClasses = FXCollections.observableArrayList(availableClasses);

            // Create tabs
            TabPane tabPane = new TabPane();
            tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

            // Tab 1: Acquisition Summary (main tab)
            Tab summaryTab = new Tab("Acquire Regions");
            VBox summaryContent = createSummaryContent(selectedClasses, modalityUI);
            summaryTab.setContent(summaryContent);

            // Tab 2: Class Selection
            Tab classTab = new Tab("Filter Classes");
            VBox classContent = createClassSelectionContent(allAvailableClasses, selectedClasses);
            classTab.setContent(classContent);

            tabPane.getTabs().addAll(summaryTab, classTab);

            // Add hierarchy listener to update when annotations change
            PathObjectHierarchyListener hierarchyListener = new PathObjectHierarchyListener() {
                @Override
                public void hierarchyChanged(PathObjectHierarchyEvent event) {
                    Platform.runLater(() -> {
                        // Update available classes
                        Set<String> currentClasses = QP.getAnnotationObjects().stream()
                                .filter(ann -> ann.getPathClass() != null)
                                .map(ann -> ann.getPathClass().getName())
                                .collect(Collectors.toSet());

                        // Add any new classes not already in the list
                        for (String className : currentClasses) {
                            if (!allAvailableClasses.contains(className)) {
                                allAvailableClasses.add(className);
                                logger.debug("Added new class to available: {}", className);
                            }
                        }

                        // Refresh the class selection tab
                        VBox newClassContent = createClassSelectionContent(allAvailableClasses, selectedClasses);
                        classTab.setContent(newClassContent);

                        // Update summary display
                        updateSummaryDisplay(selectedClasses,
                                (ListView<String>) summaryContent.lookup("#annotationList"),
                                (Label) summaryContent.lookup("#countLabel"));
                    });
                }
            };

            // Add the listener
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui != null && gui.getImageData() != null) {
                gui.getImageData().getHierarchy().addListener(hierarchyListener);
            }

            dialog.getDialogPane().setContent(tabPane);
            dialog.getDialogPane().setPrefWidth(550);
            dialog.getDialogPane().setPrefHeight(450);

            ButtonType collectButton = new ButtonType("Collect Regions", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(collectButton, cancelButton);

            // Style the collect button
            Node collectNode = dialog.getDialogPane().lookupButton(collectButton);
            collectNode.setStyle("-fx-base: #5a9fd4; -fx-font-weight: bold;");

            // Disable collect button if no annotations match
            collectNode.disableProperty().bind(
                    Bindings.createBooleanBinding(
                            () -> getMatchingAnnotations(selectedClasses).isEmpty(),
                            selectedClasses
                    )
            );

            // Set up proper result handling
            dialog.setResultConverter(button -> {
                if (button == collectButton) {
                    // Save preferences if remember is checked
                    CheckBox rememberCb = (CheckBox) classContent.lookup("#rememberCheckBox");
                    if (rememberCb != null && rememberCb.isSelected()) {
                        PersistentPreferences.setSelectedAnnotationClasses(new ArrayList<>(selectedClasses));
                    }

                    // Get angle overrides from modality UI if present
                    Map<String, Double> angleOverrides = modalityUI
                            .map(ModalityHandler.BoundingBoxUI::getAngleOverrides)
                            .orElse(null);
                    if (angleOverrides != null) {
                        logger.info("User overrode angles: {}", angleOverrides);
                    }

                    return new AcquisitionResult(new ArrayList<>(selectedClasses), true, angleOverrides);
                } else {
                    // Cancel was clicked
                    return new AcquisitionResult(Collections.emptyList(), false);
                }
            });

            // Clean up listener when dialog closes
            dialog.setOnHidden(e -> {
                if (gui != null && gui.getImageData() != null) {
                    gui.getImageData().getHierarchy().removeListener(hierarchyListener);
                }
            });

            // Set initial focus to summary tab
            Platform.runLater(() -> tabPane.getSelectionModel().select(summaryTab));

            // Use show() instead of showAndWait() to prevent blocking the JavaFX thread
            // This allows the hierarchy listener to function properly when user edits annotations
            dialog.setOnCloseRequest(e -> {
                AcquisitionResult result = dialog.getResult();
                if (result != null) {
                    future.complete(result);
                } else {
                    future.complete(new AcquisitionResult(Collections.emptyList(), false));
                }
            });

            dialog.show();
        });

        return future;
    }

    /**
     * Creates the summary content showing what will be acquired.
     */
    private static VBox createSummaryContent(ObservableList<String> selectedClasses,
                                              Optional<ModalityHandler.BoundingBoxUI> modalityUI) {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));

        // Header
        Label headerLabel = new Label("Annotations to be acquired:");
        headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        content.getChildren().add(headerLabel);

        // Annotation list
        ListView<String> annotationList = new ListView<>();
        annotationList.setId("annotationList");
        annotationList.setPrefHeight(250);
        annotationList.setStyle("-fx-font-size: 12px;");

        // Count label
        Label countLabel = new Label();
        countLabel.setId("countLabel");
        countLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        // Info label
        Label infoLabel = new Label("Tip: Use the 'Filter Classes' tab to select which annotation types to acquire");
        infoLabel.setStyle("-fx-font-size: 11px; -fx-font-style: italic;");
        infoLabel.setWrapText(true);

        // Update content when selected classes change
        selectedClasses.addListener((javafx.collections.ListChangeListener<String>) change -> {
            logger.debug("Selected classes changed: {}", selectedClasses);
            updateSummaryDisplay(selectedClasses, annotationList, countLabel);
        });

        // Initial update
        updateSummaryDisplay(selectedClasses, annotationList, countLabel);

        // Add refresh button
        Button refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> updateSummaryDisplay(selectedClasses, annotationList, countLabel));

        HBox buttonBox = new HBox(10);
        buttonBox.getChildren().add(refreshBtn);

        content.getChildren().addAll(annotationList, countLabel, buttonBox, new Separator(), infoLabel);

        // Add modality-specific UI if present (e.g., PPM angle overrides)
        modalityUI.ifPresent(ui -> {
            content.getChildren().add(ui.getNode());
        });

        return content;
    }

    /**
     * Creates the class selection content.
     */
    private static VBox createClassSelectionContent(ObservableList<String> availableClasses,
                                                    ObservableList<String> selectedClasses) {
        VBox content = new VBox(10);
        content.setPadding(new Insets(15));

        Label instructionLabel = new Label("Select which annotation classes to include:");
        instructionLabel.setStyle("-fx-font-weight: bold;");
        content.getChildren().add(instructionLabel);

        // Quick selection buttons
        HBox quickButtons = new HBox(10);
        Button selectAllBtn = new Button("All");
        Button selectNoneBtn = new Button("None");
        Button selectDefaultBtn = new Button("Common Types");
        selectDefaultBtn.setTooltip(new Tooltip("Select: " + String.join(", ", DEFAULT_CLASSES)));
        quickButtons.getChildren().addAll(selectAllBtn, selectNoneBtn, selectDefaultBtn);
        content.getChildren().add(quickButtons);

        content.getChildren().add(new Separator());

        // Checkbox list
        ScrollPane scrollPane = new ScrollPane();
        VBox checkBoxContainer = new VBox(5);
        checkBoxContainer.setPadding(new Insets(5));

        // Sort classes for consistent display
        List<String> sortedClasses = new ArrayList<>(availableClasses);
        Collections.sort(sortedClasses);

        // Create checkboxes
        List<CheckBox> checkBoxes = new ArrayList<>();
        for (String className : sortedClasses) {
            CheckBox cb = new CheckBox(className);
            cb.setUserData(className);

            // Pre-select based on current selection
            cb.setSelected(selectedClasses.contains(className));

            // Update selected classes when checkbox changes
            cb.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
                logger.debug("Checkbox for '{}' changed: {} -> {}", className, wasSelected, isSelected);
                if (isSelected && !selectedClasses.contains(className)) {
                    logger.debug("Adding '{}' to selected classes", className);
                    selectedClasses.add(className);
                } else if (!isSelected) {
                    logger.debug("Removing '{}' from selected classes", className);
                    selectedClasses.remove(className);
                }
            });

            // Highlight default classes
            if (Arrays.asList(DEFAULT_CLASSES).contains(className)) {
                cb.setStyle("-fx-font-weight: bold;");
            }

            // Show annotation count for this class
            long count = QP.getAnnotationObjects().stream()
                    .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                    .filter(ann -> ann.getPathClass() != null &&
                            className.equals(ann.getPathClass().getName()))
                    .count();

            if (count > 0) {
                cb.setText(String.format("%s (%d)", className, count));
            }

            checkBoxes.add(cb);
            checkBoxContainer.getChildren().add(cb);
        }

        scrollPane.setContent(checkBoxContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(200);
        content.getChildren().add(scrollPane);

        // Remember selection checkbox
        content.getChildren().add(new Separator());
        CheckBox rememberCb = new CheckBox("Remember these selections for next time");
        rememberCb.setId("rememberCheckBox");
        rememberCb.setSelected(PersistentPreferences.getRememberAnnotationSelection());
        content.getChildren().add(rememberCb);

        // Quick button actions
        selectAllBtn.setOnAction(e -> {
            checkBoxes.forEach(cb -> cb.setSelected(true));
        });

        selectNoneBtn.setOnAction(e -> {
            checkBoxes.forEach(cb -> cb.setSelected(false));
        });

        selectDefaultBtn.setOnAction(e -> {
            checkBoxes.forEach(cb -> {
                String className = cb.getText().split(" \\(")[0]; // Remove count
                cb.setSelected(Arrays.asList(DEFAULT_CLASSES).contains(className));
            });
        });

        return content;
    }

    /**
     * Updates the summary display with current annotations.
     */
    private static void updateSummaryDisplay(ObservableList<String> selectedClasses,
                                             ListView<String> listView,
                                             Label countLabel) {
        listView.getItems().clear();

        List<PathObject> matchingAnnotations = getMatchingAnnotations(selectedClasses);

        if (selectedClasses.isEmpty()) {
            listView.getItems().add("No annotation classes selected");
            countLabel.setText("0 annotations will be acquired");
            countLabel.setTextFill(Color.RED);
            return;
        }

        // Group by class for display
        Map<String, List<PathObject>> annotationsByClass = matchingAnnotations.stream()
                .collect(Collectors.groupingBy(ann -> ann.getPathClass().getName()));

        // Display each class with its annotations
        for (String className : selectedClasses) {
            List<PathObject> classAnnotations = annotationsByClass.getOrDefault(className, Collections.emptyList());

            if (!classAnnotations.isEmpty()) {
                listView.getItems().add(String.format("━━ %s (%d) ━━", className, classAnnotations.size()));

                // Show individual annotations with names
                for (PathObject ann : classAnnotations) {
                    String name = ann.getName();
                    if (name == null || name.isEmpty()) {
                        name = String.format("Unnamed at (%.0f, %.0f)",
                                ann.getROI().getCentroidX(),
                                ann.getROI().getCentroidY());
                    }
                    listView.getItems().add("   • " + name);
                }
            } else {
                listView.getItems().add(String.format("━━ %s (none found) ━━", className));
            }
        }

        // Update total count
        int totalCount = matchingAnnotations.size();
        countLabel.setText(String.format("%d annotation%s will be acquired",
                totalCount, totalCount == 1 ? "" : "s"));

        if (totalCount == 0) {
            countLabel.setTextFill(Color.RED);
        } else {
            countLabel.setTextFill(Color.GREEN.darker());
        }
    }

    /**
     * Gets all annotations matching the selected classes.
     */
    private static List<PathObject> getMatchingAnnotations(List<String> selectedClasses) {
        if (selectedClasses.isEmpty()) {
            return Collections.emptyList();
        }

        return QP.getAnnotationObjects().stream()
                .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                .filter(ann -> ann.getPathClass() != null &&
                        selectedClasses.contains(ann.getPathClass().getName()))
                .collect(Collectors.toList());
    }

    /**
     * Result from the acquisition dialog.
     */
    public static class AcquisitionResult {
        public final List<String> selectedClasses;
        public final boolean proceed;
        public final Map<String, Double> angleOverrides;

        public AcquisitionResult(List<String> selectedClasses, boolean proceed) {
            this(selectedClasses, proceed, null);
        }

        public AcquisitionResult(List<String> selectedClasses, boolean proceed, Map<String, Double> angleOverrides) {
            this.selectedClasses = selectedClasses;
            this.proceed = proceed;
            this.angleOverrides = angleOverrides;
        }
    }
}