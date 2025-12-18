package qupath.ext.qpsc;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.QPScopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

import java.io.IOException;
import java.util.Set;
import java.util.ResourceBundle;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;

/**
 * Entry point for the QP Scope extension.
 * <p>
 * This class retains the core template functionality:
 *   - It loads metadata (name, description, version) from a resource bundle.
 *   - It defines the required QuPath version and GitHub repository (for update checking).
 *   - It registers a menu item in QuPath's Extensions menu.
 * <p>
 * When the user selects the menu item, it delegates to QPScopeController.startWorkflow()
 * to begin the microscope control workflow.
 */
public class SetupScope implements QuPathExtension, GitHubProject {

	private static final Logger logger = LoggerFactory.getLogger(SetupScope.class);

	// Load extension metadata
	private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
	private static final String EXTENSION_NAME        = res.getString("name");
	private static final String EXTENSION_DESCRIPTION = res.getString("description");
	private static final Version EXTENSION_QUPATH_VERSION =
			Version.parse("v0.6.0");
	private static final GitHubRepo EXTENSION_REPOSITORY =
			GitHubRepo.create(EXTENSION_NAME, "uw-loci", "qupath-extension-qpsc");

	/** True if the microscope YAML passed validation. */
	private boolean configValid;

	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}

	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}

	@Override
	public GitHubRepo getRepository() {
		return EXTENSION_REPOSITORY;
	}

	@Override
	public void installExtension(QuPathGUI qupath) {
		logger.info("Installing extension: " + EXTENSION_NAME);

		// 1) Register all our persistent preferences
		QPPreferenceDialog.installPreferences(qupath);
		MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());

		// 2) Validate microscope YAML up-front via QPScopeChecks
		configValid = QPScopeChecks.validateMicroscopeConfig();
		if (!configValid) {
			// Warn user once on the FX thread
			Platform.runLater(() ->
					Dialogs.showWarningNotification(
							EXTENSION_NAME + " configuration",
							"Some required microscope settings are missing or invalid.\n" +
									"All workflows except Test have been disabled.\n" +
									"Please correct your YAML and restart QuPath."
					)
			);
		}

		// 3) Build our menu on the FX thread
		Platform.runLater(() -> addMenuItem(qupath));
	}

	private void addMenuItem(QuPathGUI qupath) {
		// Create or get the top level Extensions > QP Scope menu
		var extensionMenu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);

		// === MAIN WORKFLOW MENU ITEMS ===

		// 1) Bounded Acquisition - acquire tiles from a defined bounding box region
		MenuItem boundedAcquisitionOption = new MenuItem(res.getString("menu.boundedAcquisition"));
		boundedAcquisitionOption.setDisable(!configValid);
		setMenuItemTooltip(boundedAcquisitionOption,
				"Start a new acquisition by defining a rectangular region using stage coordinates. " +
				"Use this when you want to scan a specific area without a pre-existing image.");
		boundedAcquisitionOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("boundedAcquisition");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// 2) Existing Image Acquisition - acquire from annotations on an existing image
		MenuItem existingImageOption = new MenuItem(res.getString("menu.existingimage"));
		existingImageOption.disableProperty().bind(
				Bindings.or(
						Bindings.createBooleanBinding(
								() -> qupath.getImageData() == null,
								qupath.imageDataProperty()
						),
						Bindings.createBooleanBinding(
								() -> !configValid,
								qupath.imageDataProperty()
						)
				)
		);
		setMenuItemTooltip(existingImageOption,
				"Acquire high-resolution images of annotated regions in the currently open image. " +
				"Draw annotations on a macro or overview image, then use this to scan those specific areas.");
		existingImageOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("existingImage");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// 3) Microscope alignment workflow (only enabled if image has macro)
		MenuItem alignmentOption = new MenuItem(res.getString("menu.microscopeAlignment"));
		alignmentOption.disableProperty().bind(
				Bindings.createBooleanBinding(
						() -> {
							if (!configValid) {
								return true;
							}
							var imageData = qupath.getImageData();
							if (imageData == null) {
								return true;
							}
							try {
								var server = imageData.getServer();
								if (server == null) {
									return true;
								}
								var associatedImages = server.getAssociatedImageList();
								if (associatedImages == null) {
									return true;
								}
								return !MacroImageUtility.isMacroImageAvailable(qupath);
							} catch (Exception e) {
								logger.error("Error in macro menu binding", e);
								return true;
							}
						},
						qupath.imageDataProperty()
				)
		);
		setMenuItemTooltip(alignmentOption,
				"Create or refine the coordinate alignment between a slide scanner's macro image and the microscope stage. " +
				"Run this once per scanner to enable accurate targeting in future acquisitions.");
		alignmentOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("microscopeAlignment");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// 4) Stage Control - manual stage movement interface
		MenuItem stageControlOption = new MenuItem(res.getString("menu.stagecontrol"));
		setMenuItemTooltip(stageControlOption,
				"Open a simple interface to manually move the microscope stage to specific X, Y, Z positions. " +
				"Useful for testing connectivity and exploring the slide.");
		stageControlOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("basicStageInterface");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// 5) Stage Map - visual stage position display
		MenuItem stageMapOption = new MenuItem(res.getString("menu.stageMap"));
		setMenuItemTooltip(stageMapOption,
				"Open a visual map showing the stage insert with slide positions and current objective location. " +
				"The map updates in real-time and allows double-click navigation to move the objective. " +
				"Use the dropdown to switch between different insert configurations (single slide, multi-slide).");
		stageMapOption.setOnAction(e -> StageMapWindow.show());

		// === UTILITIES SUBMENU ===
		Menu utilitiesMenu = new Menu("Utilities");

		// Background collection (for flat field correction)
		MenuItem backgroundCollectionOption = new MenuItem(res.getString("menu.backgroundCollection"));
		backgroundCollectionOption.setDisable(!configValid);
		setMenuItemTooltip(backgroundCollectionOption,
				"Capture background images for flat-field correction. " +
				"Move to a blank area of the slide and acquire reference images to correct uneven illumination.");
		backgroundCollectionOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("backgroundCollection");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// Polarizer calibration (PPM only)
		MenuItem polarizerCalibrationOption = new MenuItem(res.getString("menu.polarizerCalibration"));
		polarizerCalibrationOption.setDisable(!configValid);
		setMenuItemTooltip(polarizerCalibrationOption,
				"Calibrate the polarizer rotation stage for polarized light microscopy (PPM). " +
				"Determines the correct rotation angles for optimal birefringence imaging.");
		polarizerCalibrationOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("polarizerCalibration");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// PPM Rotation Sensitivity Test
		MenuItem ppmSensitivityTestOption = new MenuItem(res.getString("menu.ppmSensitivityTest"));
		ppmSensitivityTestOption.setDisable(!configValid);
		setMenuItemTooltip(ppmSensitivityTestOption,
				"Test PPM rotation stage sensitivity by acquiring images at precise angles. " +
				"Analyzes the impact of angular deviations on image quality and birefringence calculations. " +
				"Provides comprehensive analysis reports for validation and optimization.");
		ppmSensitivityTestOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("ppmSensitivityTest");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// PPM Birefringence Optimization
		MenuItem birefringenceOptimizationOption = new MenuItem(res.getString("menu.birefringenceOptimization"));
		birefringenceOptimizationOption.setDisable(!configValid);
		setMenuItemTooltip(birefringenceOptimizationOption,
				"Find the optimal polarizer angle for maximum birefringence signal contrast. " +
				"Systematically tests angles by acquiring paired images (+theta, -theta) and " +
				"computing their difference. Results include optimal angles, signal metrics, and " +
				"visualization plots. Supports multiple exposure modes (interpolate, calibrate, fixed).");
		birefringenceOptimizationOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("birefringenceOptimization");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// Autofocus settings editor
		MenuItem autofocusEditorOption = new MenuItem(res.getString("menu.autofocusEditor"));
		autofocusEditorOption.setDisable(!configValid);
		setMenuItemTooltip(autofocusEditorOption,
				"Configure autofocus parameters for each objective lens. " +
				"Adjust search range, step size, and scoring method to optimize focus quality.");
		autofocusEditorOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("autofocusEditor");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// Server Connection Settings
		MenuItem serverConnectionOption = new MenuItem(res.getString("menu.serverConnection"));
		setMenuItemTooltip(serverConnectionOption,
				"Configure and test the connection to the microscope control server. " +
				"Set the server address, port, and verify communication with the microscope hardware.");
		serverConnectionOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("serverConnection");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// Autofocus parameter benchmark
		MenuItem autofocusBenchmarkOption = new MenuItem(res.getString("menu.autofocusBenchmark"));
		autofocusBenchmarkOption.setDisable(!configValid);
		setMenuItemTooltip(autofocusBenchmarkOption,
				"Run systematic testing of autofocus parameters to find optimal settings. " +
				"Tests multiple combinations of n_steps, search_range, interpolation methods, and " +
				"score metrics at various distances from focus. Results include timing and accuracy " +
				"measurements. Quick mode: 10-15 min, Full benchmark: 30-60 min.");
		autofocusBenchmarkOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("autofocusBenchmark");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// Add items to utilities submenu
		utilitiesMenu.getItems().addAll(
				alignmentOption,
				stageMapOption,
				new SeparatorMenuItem(),
				backgroundCollectionOption,
				polarizerCalibrationOption,
				ppmSensitivityTestOption,
				birefringenceOptimizationOption,
				autofocusEditorOption,
				autofocusBenchmarkOption,
				new SeparatorMenuItem(),
				serverConnectionOption
		);

		// === BUILD FINAL MENU ===
		extensionMenu.getItems().addAll(
				boundedAcquisitionOption,
				existingImageOption,
				new SeparatorMenuItem(),
				stageControlOption,
				new SeparatorMenuItem(),
				utilitiesMenu
		);

		logger.info("Menu items added for extension: " + EXTENSION_NAME);
	}

	/**
	 * Sets a tooltip on a MenuItem by using a Label as the graphic.
	 * Since JavaFX MenuItem doesn't directly support tooltips, we install
	 * the tooltip on the menu item's internal label node when it's shown.
	 *
	 * @param menuItem the menu item to add tooltip to
	 * @param tooltipText the tooltip text to display
	 */
	private void setMenuItemTooltip(MenuItem menuItem, String tooltipText) {
		Tooltip tooltip = new Tooltip(tooltipText);
		tooltip.setShowDelay(Duration.millis(500));
		tooltip.setShowDuration(Duration.seconds(30));
		tooltip.setWrapText(true);
		tooltip.setMaxWidth(350);

		// Install tooltip when the menu item's parent menu is shown
		menuItem.parentPopupProperty().addListener((obs, oldPopup, newPopup) -> {
			if (newPopup != null) {
				newPopup.setOnShown(e -> {
					// Find the label node for this menu item and install tooltip
					var node = menuItem.getStyleableNode();
					if (node != null) {
						Tooltip.install(node, tooltip);
					}
				});
			}
		});
	}
}