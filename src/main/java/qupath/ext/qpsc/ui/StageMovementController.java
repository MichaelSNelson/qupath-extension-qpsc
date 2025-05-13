package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import qupath.ext.qpsc.controller.MicroscopeController;

import java.util.ResourceBundle;

/**
 * Pops up a little dialog to let you move X/Y, Z, or R (polarizer) independently,
 * but guards against sending X/Y or Z outside their configured limits.
 */
public class StageMovementController {

    public static void showTestStageMovementDialog() {
        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
            Dialog<Void> dlg = new Dialog<>();
            dlg.setTitle(res.getString("stageMovement.title"));
            dlg.setHeaderText(res.getString("stageMovement.header"));

            // --- Fields and status labels ---
            TextField xField = new TextField();
            TextField yField = new TextField();
            Label xyStatus = new Label();

            TextField zField = new TextField();
            Label zStatus = new Label();

            TextField rField = new TextField();
            Label rStatus = new Label();

            // --- Initialize from hardware ---
            try {
                double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                xField.setText(String.format("%.2f", xy[0]));
                yField.setText(String.format("%.2f", xy[1]));
            } catch (Exception ignored) {}

            try {
                double z = MicroscopeController.getInstance().getStagePositionZ();
                zField.setText(String.format("%.2f", z));
            } catch (Exception ignored) {}

            try {
                double r = MicroscopeController.getInstance().getStagePositionR();
                rField.setText(String.format("%.2f", r));
            } catch (Exception ignored) {}

            // --- Buttons ---
            Button moveXYBtn = new Button(res.getString("stageMovement.button.moveXY"));
            Button moveZBtn  = new Button(res.getString("stageMovement.button.moveZ"));
            Button moveRBtn  = new Button(res.getString("stageMovement.button.moveR"));

            // --- Actions (dialog stays open) ---
            moveXYBtn.setOnAction(e -> {
                try {
                    double x = Double.parseDouble(xField.getText());
                    double y = Double.parseDouble(yField.getText());
                    // *** BOUNDS CHECK ***
                    if (!MicroscopeController.getInstance().isWithinBoundsXY(x, y)) {
                        UIFunctions.notifyUserOfError(
                                res.getString("stageMovement.error.outOfBoundsXY"),
                                res.getString("stageMovement.title"));
                        UIFunctions.showAlertDialog(res.getString("stageMovement.error.outOfBoundsXY"));
                        return;
                    }
                    MicroscopeController.getInstance().moveStageXY(x, y);
                    xyStatus.setText(
                            String.format(res.getString("stageMovement.status.xyMoved"), x, y));
                } catch (Exception ex) {
                    UIFunctions.notifyUserOfError(
                            ex.getMessage(), res.getString("stageMovement.title"));
                }
            });

            moveZBtn.setOnAction(e -> {
                try {
                    double z = Double.parseDouble(zField.getText());
                    // *** BOUNDS CHECK ***
                    if (!MicroscopeController.getInstance().isWithinBoundsZ(z)) {
                        UIFunctions.notifyUserOfError(
                                res.getString("stageMovement.error.outOfBoundsZ"),
                                res.getString("stageMovement.title"));
                        UIFunctions.showAlertDialog(res.getString("stageMovement.error.outOfBoundsZ"));
                        return;
                    }
                    MicroscopeController.getInstance().moveStageZ(z);
                    zStatus.setText(
                            String.format(res.getString("stageMovement.status.zMoved"), z));
                } catch (Exception ex) {
                    UIFunctions.notifyUserOfError(
                            ex.getMessage(), res.getString("stageMovement.title"));
                }
            });

            moveRBtn.setOnAction(e -> {
                try {
                    double r = Double.parseDouble(rField.getText());
                    // no bounds for R
                    MicroscopeController.getInstance().moveStageR(r);
                    rStatus.setText(
                            String.format(res.getString("stageMovement.status.rMoved"), r));
                } catch (Exception ex) {
                    UIFunctions.notifyUserOfError(
                            ex.getMessage(), res.getString("stageMovement.title"));
                }
            });

            // --- Layout ---
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(20));

            // XY
            grid.add(new Label(res.getString("stageMovement.label.x")), 0, 0);
            grid.add(xField, 1, 0);
            grid.add(new Label(res.getString("stageMovement.label.y")), 0, 1);
            grid.add(yField, 1, 1);
            grid.add(moveXYBtn, 2, 0, 1, 2);
            grid.add(xyStatus,    1, 2, 2, 1);

            // Z
            grid.add(new Label(res.getString("stageMovement.label.z")), 0, 3);
            grid.add(zField, 1, 3);
            grid.add(moveZBtn, 2, 3);
            grid.add(zStatus,  1, 4, 2, 1);

            // R
            grid.add(new Label(res.getString("stageMovement.label.r")), 0, 5);
            grid.add(rField, 1, 5);
            grid.add(moveRBtn, 2, 5);
            grid.add(rStatus, 1, 6, 2, 1);

            dlg.getDialogPane().setContent(grid);
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dlg.initModality(Modality.NONE);
            dlg.show();
        });
    }
}

