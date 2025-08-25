package qupath.ext.qpsc.modality.ppm.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Controller for selecting PPM angles and exposures.
 */
public class PPMAngleSelectionController {

    private static final Logger logger = LoggerFactory.getLogger(PPMAngleSelectionController.class);

    public static class AngleExposureResult {
        public final List<AngleExposure> angleExposures;
        public AngleExposureResult(List<AngleExposure> angleExposures) {
            this.angleExposures = angleExposures;
        }
        public List<Double> getAngles() {
            return angleExposures.stream().map(ae -> ae.angle).collect(Collectors.toList());
        }
    }

    public static class AngleExposure {
        public final double angle;
        public final int exposureMs;
        public AngleExposure(double angle, int exposureMs) {
            this.angle = angle;
            this.exposureMs = exposureMs;
        }
        @Override
        public String toString() {
            return String.format("%.1f° @ %dms", angle, exposureMs);
        }
    }

    public static CompletableFuture<AngleExposureResult> showDialog(double plusAngle, double minusAngle) {
        CompletableFuture<AngleExposureResult> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            Dialog<AngleExposureResult> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("PPM Angle Selection");
            dialog.setHeaderText("Select polarization angles (in tick marks) and exposure times for acquisition:");

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(10));

            UnaryOperator<TextFormatter.Change> intFilter = change -> {
                String newText = change.getControlNewText();
                if (newText.isEmpty()) return change;
                try {
                    int value = Integer.parseInt(newText);
                    if (value > 0) return change;
                } catch (NumberFormatException ignored) {}
                return null;
            };

            grid.add(new Label("Angle"),0,0);
            grid.add(new Label("Exposure (ms)"),2,0);

            CheckBox minusCheck = new CheckBox(String.format("%.1f 'degrees'", minusAngle));
            minusCheck.setSelected(PPMPreferences.getMinusSelected());
            TextField minusExposure = new TextField(String.valueOf(PPMPreferences.getMinusExposureMs()));
            minusExposure.setTextFormatter(new TextFormatter<>(intFilter));
            minusExposure.setPrefWidth(80);
            minusExposure.setDisable(!minusCheck.isSelected());
            grid.add(minusCheck,0,1);
            grid.add(minusExposure,2,1);

            CheckBox zeroCheck = new CheckBox("0 'degrees'");
            zeroCheck.setSelected(PPMPreferences.getZeroSelected());
            TextField zeroExposure = new TextField(String.valueOf(PPMPreferences.getZeroExposureMs()));
            zeroExposure.setTextFormatter(new TextFormatter<>(intFilter));
            zeroExposure.setPrefWidth(80);
            zeroExposure.setDisable(!zeroCheck.isSelected());
            grid.add(zeroCheck,0,2);
            grid.add(zeroExposure,2,2);

            CheckBox plusCheck = new CheckBox(String.format("%.1f 'degrees'", plusAngle));
            plusCheck.setSelected(PPMPreferences.getPlusSelected());
            TextField plusExposure = new TextField(String.valueOf(PPMPreferences.getPlusExposureMs()));
            plusExposure.setTextFormatter(new TextFormatter<>(intFilter));
            plusExposure.setPrefWidth(80);
            plusExposure.setDisable(!plusCheck.isSelected());
            grid.add(plusCheck,0,3);
            grid.add(plusExposure,2,3);

            minusCheck.selectedProperty().addListener((obs,o,s)->{
                minusExposure.setDisable(!s);
                if(!s) minusExposure.clear();
                PPMPreferences.setMinusSelected(s);
                logger.debug("PPM minus tick selection updated to: {}", s);
            });
            zeroCheck.selectedProperty().addListener((obs,o,s)->{
                zeroExposure.setDisable(!s);
                if(!s) zeroExposure.clear();
                PPMPreferences.setZeroSelected(s);
                logger.debug("PPM zero tick selection updated to: {}", s);
            });
            plusCheck.selectedProperty().addListener((obs,o,s)->{
                plusExposure.setDisable(!s);
                if(!s) plusExposure.clear();
                PPMPreferences.setPlusSelected(s);
                logger.debug("PPM plus tick selection updated to: {}", s);
            });

            minusExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setMinusExposureMs(Integer.parseInt(n)); });
            zeroExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setZeroExposureMs(Integer.parseInt(n)); });
            plusExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setPlusExposureMs(Integer.parseInt(n)); });

            Label info = new Label("Each selected angle will be acquired with the specified exposure time.");
            Button selectAll = new Button("Select All");
            Button selectNone = new Button("Select None");
            selectAll.setOnAction(e->{minusCheck.setSelected(true);zeroCheck.setSelected(true);plusCheck.setSelected(true);});
            selectNone.setOnAction(e->{minusCheck.setSelected(false);zeroCheck.setSelected(false);plusCheck.setSelected(false);});
            HBox quickButtons = new HBox(10, selectAll, selectNone);

            VBox content = new VBox(10, info, grid, new Separator(), quickButtons);
            content.setPadding(new Insets(20));
            dialog.getDialogPane().setContent(content);

            ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);
            Node okNode = dialog.getDialogPane().lookupButton(okType);

            BooleanBinding valid = Bindings.createBooleanBinding(() -> {
                boolean any = minusCheck.isSelected() || zeroCheck.isSelected() || plusCheck.isSelected();
                if(!any) return false;
                if(minusCheck.isSelected() && !isValidPosInt(minusExposure.getText())) return false;
                if(zeroCheck.isSelected() && !isValidPosInt(zeroExposure.getText())) return false;
                if(plusCheck.isSelected() && !isValidPosInt(plusExposure.getText())) return false;
                return true;
            }, minusCheck.selectedProperty(), zeroCheck.selectedProperty(), plusCheck.selectedProperty(),
            minusExposure.textProperty(), zeroExposure.textProperty(), plusExposure.textProperty());
            okNode.disableProperty().bind(valid.not());

            dialog.setResultConverter(button -> {
                if(button==okType){
                    List<AngleExposure> list = new ArrayList<>();
                    if(minusCheck.isSelected()) list.add(new AngleExposure(minusAngle,Integer.parseInt(minusExposure.getText())));
                    if(zeroCheck.isSelected()) list.add(new AngleExposure(0.0,Integer.parseInt(zeroExposure.getText())));
                    if(plusCheck.isSelected()) list.add(new AngleExposure(plusAngle,Integer.parseInt(plusExposure.getText())));
                    logger.info("PPM angles and exposures selected: {}", list);
                    return new AngleExposureResult(list);
                }
                return null;
            });

            dialog.showAndWait().ifPresentOrElse(future::complete, ()->future.cancel(true));
        });
        return future;
    }

    private static boolean isValidPosInt(String text){
        try{int v=Integer.parseInt(text);return v>0;}catch(NumberFormatException e){return false;}
    }
}
