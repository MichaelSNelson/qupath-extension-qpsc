package qupath.ext.qpsc.modality.ppm.ui;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.HashMap;
import java.util.Map;

/**
 * UI component allowing per-acquisition override of PPM angles.
 */
public class PPMBoundingBoxUI implements ModalityHandler.BoundingBoxUI {

    private final VBox root;
    private final CheckBox overrideAngles;
    private final Spinner<Double> plusSpinner;
    private final Spinner<Double> minusSpinner;

    @SuppressWarnings("unchecked")
    public PPMBoundingBoxUI() {
        root = new VBox(5);

        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty());

        double defaultPlus = 7.0;
        double defaultMinus = -7.0;

        java.util.List<?> angles = mgr.getList("modalities", "ppm", "rotation_angles");
        if (angles != null) {
            for (Object angleObj : angles) {
                if (angleObj instanceof java.util.Map<?, ?> angle) {
                    Object name = angle.get("name");
                    Object tickObj = angle.get("tick");
                    if (name != null && tickObj instanceof Number) {
                        double tick = ((Number) tickObj).doubleValue();
                        if ("positive".equals(name.toString())) defaultPlus = tick;
                        else if ("negative".equals(name.toString())) defaultMinus = tick;
                    }
                }
            }
        }

        Label label = new Label("PPM Polarization Angles:");
        label.setStyle("-fx-font-weight: bold;");

        overrideAngles = new CheckBox("Override default angles for this acquisition");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        grid.setDisable(true);

        // Load saved preferences or use defaults
        double savedPlusAngle = PPMPreferences.getOverridePlusAngle();
        double savedMinusAngle = PPMPreferences.getOverrideMinusAngle();
        boolean overrideEnabled = PPMPreferences.getAngleOverrideEnabled();

        plusSpinner = new Spinner<>();
        plusSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(-180, 180, savedPlusAngle, 0.5));
        plusSpinner.setEditable(true);
        plusSpinner.setPrefWidth(100);

        minusSpinner = new Spinner<>();
        minusSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(-180, 180, savedMinusAngle, 0.5));
        minusSpinner.setEditable(true);
        minusSpinner.setPrefWidth(100);

        grid.add(new Label("Plus angle (ticks):"), 0, 0);
        grid.add(plusSpinner, 1, 0);
        grid.add(new Label("Minus angle (ticks):"), 0, 1);
        grid.add(minusSpinner, 1, 1);

        // Set checkbox to saved state
        overrideAngles.setSelected(overrideEnabled);
        grid.setDisable(!overrideEnabled);

        // Save preferences when values change
        overrideAngles.selectedProperty().addListener((obs, old, sel) -> {
            grid.setDisable(!sel);
            PPMPreferences.setAngleOverrideEnabled(sel);
        });

        plusSpinner.valueProperty().addListener((obs, old, newVal) -> {
            if (newVal != null) {
                PPMPreferences.setOverridePlusAngle(newVal);
            }
        });

        minusSpinner.valueProperty().addListener((obs, old, newVal) -> {
            if (newVal != null) {
                PPMPreferences.setOverrideMinusAngle(newVal);
            }
        });

        root.getChildren().addAll(new Separator(), label, overrideAngles, grid);
    }

    @Override
    public Node getNode() {
        return root;
    }

    @Override
    public Map<String, Double> getAngleOverrides() {
        if (!overrideAngles.isSelected()) {
            return null;
        }
        Map<String, Double> map = new HashMap<>();
        map.put("plus", plusSpinner.getValue());
        map.put("minus", minusSpinner.getValue());
        return map;
    }
}
