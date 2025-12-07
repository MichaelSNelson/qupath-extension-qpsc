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
			GitHubRepo.create(EXTENSION_NAME, "MichaelSNelson", "qupath-extension-qpsc");

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

		// === WORKFLOW MENU ITEMS ===

		// 1) Bounded Acquisition (new unified dialog workflow)
		MenuItem boundedAcquisitionOption = new MenuItem(res.getString("menu.boundedAcquisition"));
		boundedAcquisitionOption.setDisable(!configValid);
		boundedAcquisitionOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("boundedAcquisition");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// 2) Start with a bounding box workflow (legacy - to be deprecated)
		MenuItem boundingBoxOption = new MenuItem(res.getString("menu.boundingbox"));
		boundingBoxOption.setDisable(!configValid);
		boundingBoxOption.setOnAction(e ->
				{
					try {
						QPScopeController.getInstance().startWorkflow("boundingBox");
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				}
		);

		// 2) Start with existing image (only enabled if an image is open & config is valid)
		MenuItem existingImageOption = new MenuItem(res.getString("menu.existingimage"));
		existingImageOption.disableProperty().bind(
				Bindings.or(
						// no image open?
						Bindings.createBooleanBinding(
								() -> qupath.getImageData() == null,
								qupath.imageDataProperty()
						),
						// or config invalid?
						Bindings.createBooleanBinding(
								() -> !configValid,
								qupath.imageDataProperty()
						)
				)
		);
		existingImageOption.setOnAction(e ->
				{
					try {
						QPScopeController.getInstance().startWorkflow("existingImage");
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				}
		);

		// 2b) Existing Image V2 - Consolidated dialog version (New UI)
		MenuItem existingImageV2Option = new MenuItem("Acquire from Existing Image (New UI)");
		existingImageV2Option.disableProperty().bind(
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
		existingImageV2Option.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("existingImageV2");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// 3) Microscope alignment workflow (only enabled if image has macro)
		MenuItem alignmentOption = new MenuItem(res.getString("menu.microscopeAlignment"));

		logger.info("Creating microscope alignment menu item. Config valid: {}", configValid);

		// Create a simple binding like existing image uses
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

								// Get associated images list
								var associatedImages = server.getAssociatedImageList();
								if (associatedImages == null) {
									return true;
								}

								// Check if any associated image contains "macro" in its name
								// This handles both "macro" and "Series X (macro image)" formats
								boolean hasMacro = MacroImageUtility.isMacroImageAvailable(qupath);
								return !hasMacro;

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

		// 4) Background collection (for flat field correction)
		MenuItem backgroundCollectionOption = new MenuItem(res.getString("menu.backgroundCollection"));
		backgroundCollectionOption.setDisable(!configValid);
		backgroundCollectionOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("backgroundCollection");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// 5) Polarizer calibration (PPM only)
		MenuItem polarizerCalibrationOption = new MenuItem(res.getString("menu.polarizerCalibration"));
		polarizerCalibrationOption.setDisable(!configValid);
		polarizerCalibrationOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("polarizerCalibration");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// 6) Autofocus settings editor
		MenuItem autofocusEditorOption = new MenuItem(res.getString("menu.autofocusEditor"));
		autofocusEditorOption.setDisable(!configValid);
		autofocusEditorOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("autofocusEditor");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// === UTILITY MENU ITEMS ===

		// 6) Basic stage control (test only)
		MenuItem stageControlOption = new MenuItem(res.getString("menu.stagecontrol"));
		stageControlOption.setOnAction(e ->
				{
					try {
						QPScopeController.getInstance().startWorkflow("basicStageInterface");
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				}
		);

		// 5) Server Connection Settings
		MenuItem serverConnectionOption = new MenuItem("Server Connection Settings...");
		serverConnectionOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("serverConnection");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		// 6) Testing (TODO: remove before release)
		MenuItem testOption = new MenuItem("Test");
		testOption.setOnAction(e ->
				{
					try {
						QPScopeController.getInstance().startWorkflow("test");
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				}
		);

		// Add all menu items with proper organization
		extensionMenu.getItems().addAll(
				// Main workflows
				boundedAcquisitionOption,
				existingImageOption,
				existingImageV2Option,  // New consolidated dialog version
				alignmentOption,
				new SeparatorMenuItem(),  // Visual separator
				boundingBoxOption,  // Legacy workflow (to be deprecated)
				// Calibration and configuration
				backgroundCollectionOption,
				polarizerCalibrationOption,
				autofocusEditorOption,
				new SeparatorMenuItem(),  // Visual separator
				// Utilities
				stageControlOption,
				serverConnectionOption,
				new SeparatorMenuItem(),  // Visual separator
				// Development/Testing
				testOption
		);

		logger.info("Menu items added for extension: " + EXTENSION_NAME);
	}
}