package qupath.ext.qpsc;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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
		alignmentOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("microscopeAlignment");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// 4) Stage Control - manual stage movement interface
		MenuItem stageControlOption = new MenuItem(res.getString("menu.stagecontrol"));
		stageControlOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("basicStageInterface");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// === UTILITIES SUBMENU ===
		Menu utilitiesMenu = new Menu("Utilities");

		// Background collection (for flat field correction)
		MenuItem backgroundCollectionOption = new MenuItem(res.getString("menu.backgroundCollection"));
		backgroundCollectionOption.setDisable(!configValid);
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
		polarizerCalibrationOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("polarizerCalibration");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// Autofocus settings editor
		MenuItem autofocusEditorOption = new MenuItem(res.getString("menu.autofocusEditor"));
		autofocusEditorOption.setDisable(!configValid);
		autofocusEditorOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("autofocusEditor");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// Server Connection Settings
		MenuItem serverConnectionOption = new MenuItem(res.getString("menu.serverConnection"));
		serverConnectionOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("serverConnection");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// Add items to utilities submenu
		utilitiesMenu.getItems().addAll(
				backgroundCollectionOption,
				polarizerCalibrationOption,
				autofocusEditorOption,
				new SeparatorMenuItem(),
				serverConnectionOption
		);

		// === BUILD FINAL MENU ===
		extensionMenu.getItems().addAll(
				boundedAcquisitionOption,
				existingImageOption,
				alignmentOption,
				new SeparatorMenuItem(),
				stageControlOption,
				new SeparatorMenuItem(),
				utilitiesMenu
		);

		logger.info("Menu items added for extension: " + EXTENSION_NAME);
	}
}