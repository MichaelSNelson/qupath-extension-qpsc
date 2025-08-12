package qupath.ext.qpsc.modalities.ppm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modalities.ppm.PPMConfiguration;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.HashMap;
import java.util.Map;

/**
 * UI panel for PPM-specific configuration in dialogs.
 * Provides controls for overriding default PPM angles.
 *
 * @author Mike Nelson
 * @since 4.0
 */
public class PPMConfigurationPanel {
    private static final Logger logger = LoggerFactory.getLogger(PPMConfigurationPanel.class);

    /**
     * Creates a PPM configuration panel for use in dialogs.
     *
     * @param configManager The microscope configuration manager
     * @return VBox containing the PPM configuration UI
     */
    public static VBox create(MicroscopeConfigManager configManager) {
        VBox ppmConfig = new VBox(5);

        // Read default angles from config
        PPMConfiguration.PPMAngles defaultAngles = PPMConfiguration.readPPMAngles(configManager);

        Label ppmLabel = new Label("PPM Polarization Angles:");
        ppmLabel.setStyle("-fx-font-weight: bold;");

        CheckBox overrideAngles = new CheckBox("Override default angles for this acquisition");

        GridPane angleGrid = new GridPane();
        angleGrid.setHgap(10);
        angleGrid.setVgap(5);
        angleGrid.setDisable(true);
        angleGrid.setPadding(new Insets(5, 0, 0, 20));

        // Create spinners for angle adjustment
        Label plusLabel = new Label("Plus angle (ticks):");
        Spinner<Double> plusSpinner = new Spinner<>(-180.0, 180.0, defaultAngles.plusTicks, 0.5);
        plusSpinner.setEditable(true);
        plusSpinner.setPrefWidth(100);

        Label minusLabel = new Label("Minus angle (ticks):");
        Spinner<Double> minusSpinner = new Spinner<>(-180.0, 180.0, defaultAngles.minusTicks, 0.5);
        minusSpinner.setEditable(true);
        minusSpinner.setPrefWidth(100);

        // Add angle display labels
        Label plusDegrees = new Label(String.format("(%.1f°)", PPMConfiguration.ticksToAngle(defaultAngles.plusTicks)));
        Label minusDegrees = new Label(String.format("(%.1f°)", PPMConfiguration.ticksToAngle(defaultAngles.minusTicks)));

        // Update degree labels when spinners change
        plusSpinner.valueProperty().addListener((obs, old, newVal) -> {
            plusDegrees.setText(String.format("(%.1f°)", PPMConfiguration.ticksToAngle(newVal)));
        });

        minusSpinner.valueProperty().addListener((obs, old, newVal) -> {
            minusDegrees.setText(String.format("(%.1f°)", PPMConfiguration.ticksToAngle(newVal)));
        });

        // Layout the grid
        angleGrid.add(plusLabel, 0, 0);
        angleGrid.add(plusSpinner, 1, 0);
        angleGrid.add(plusDegrees, 2, 0);

        angleGrid.add(minusLabel, 0, 1);
        angleGrid.add(minusSpinner, 1, 1);
        angleGrid.add(minusDegrees, 2, 1);

        // Enable/disable based on checkbox
        overrideAngles.selectedProperty().addListener((obs, old, selected) -> {
            angleGrid.setDisable(!selected);
        });

        // Add info label
        Label infoLabel = new Label("Note: Zero angle (0°) and brightfield (90°) cannot be overridden");
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        ppmConfig.getChildren().addAll(
                new Separator(),
                ppmLabel,
                overrideAngles,
                angleGrid,
                infoLabel
        );

        // Store references for retrieval
        ppmConfig.getProperties().put("overrideAngles", overrideAngles);
        ppmConfig.getProperties().put("plusSpinner", plusSpinner);
        ppmConfig.getProperties().put("minusSpinner", minusSpinner);

        return ppmConfig;
    }

    /**
     * Extracts angle override values from a PPM configuration panel.
     *
     * @param ppmConfigPanel The panel created by create()
     * @return Map of angle overrides, or null if not overriding
     */
    public static Map<String, Double> getAngleOverrides(VBox ppmConfigPanel) {
        if (ppmConfigPanel == null || ppmConfigPanel.getProperties().isEmpty()) {
            return null;
        }

        CheckBox override = (CheckBox) ppmConfigPanel.getProperties().get("overrideAngles");
        if (override == null || !override.isSelected()) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Spinner<Double> plusSpin = (Spinner<Double>) ppmConfigPanel.getProperties().get("plusSpinner");
        @SuppressWarnings("unchecked")
        Spinner<Double> minusSpin = (Spinner<Double>) ppmConfigPanel.getProperties().get("minusSpinner");

        Map<String, Double> overrides = new HashMap<>();
        overrides.put("plus", plusSpin.getValue());
        overrides.put("minus", minusSpin.getValue());

        logger.info("PPM angle overrides: plus={}, minus={}", plusSpin.getValue(), minusSpin.getValue());

        return overrides;
    }
}