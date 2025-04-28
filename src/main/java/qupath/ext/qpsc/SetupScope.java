package qupath.ext.qpsc;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.QPScopeController;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import java.util.Set;
import java.util.ResourceBundle;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Entry point for the QP Scope extension.
 * <p>
 * This class retains the core template functionality:
 *   - It loads metadata (name, description, version) from a resource bundle.
 *   - It defines the required QuPath version and GitHub repository (for update checking).
 *   - It registers a menu item in QuPath’s Extensions menu.
 * <p>
 * When the user selects the menu item, it delegates to QPScopeController.startWorkflow()
 * to begin the microscope control workflow.
 */
public class SetupScope implements QuPathExtension, GitHubProject {

	private static final Logger logger = LoggerFactory.getLogger(SetupScope.class);
	/** Remember whether our YAML passed validation */
	private boolean configValid;
	// Load extension metadata from the resource bundle (place your properties file under src/main/resources)
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
	private static final String EXTENSION_NAME = resources.getString("name");
	private static final String EXTENSION_DESCRIPTION = resources.getString("description");
	//private static final String EXTENSION_VERSION = resources.getString("version");

	/**
	 * QuPath version that the extension is designed to work with.
	 * This enables QuPath to warn users if there is a version mismatch.
	 */
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");

	/**
	 * GitHub repository where this extension is maintained.
	 * This allows QuPath to help users find updates.
	 */
	private static final GitHubRepo EXTENSION_REPOSITORY = GitHubRepo.create(
			EXTENSION_NAME, "MichaelSNelson", "qupath-extension-qpsc");

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

	/**
	 * Called by QuPath to install the extension.
	 * This method registers a new menu item under the Extensions menu.
	 */
	@Override
	public void installExtension(QuPathGUI qupath) {
		logger.info("Installing extension: " + EXTENSION_NAME);

		// 1) Validate microscope YAML up front
		configValid = validateMicroscopeConfig();
		if (!configValid) {
			// Warn user once
			Platform.runLater(() -> Dialogs.showWarningNotification(
					EXTENSION_NAME + " configuration",
					"Some required microscope settings are missing or invalid.\n" +
							"All workflows except “Test” have been disabled.\n" +
							"Please correct your YAML and restart QuPath."
			));
		}

		// 2) Fire up the menu on the JavaFX thread
		Platform.runLater(() -> addMenuItem(qupath));
	}
	/**
	 * Checks for required keys in your YAML via the singleton manager.
	 * @return true if everything is present
	 */
	private boolean validateMicroscopeConfig() {
		// Define each nested path of keys you require
		Set<String[]> required = Set.of(
				new String[]{"microscope", "name"},
				new String[]{"microscope", "serialNumber"},
				new String[]{"parts", "stage", "type"},
				new String[]{"parts", "camera", "pixelSize"}
				// …add whatever else you absolutely need…
		);
//TODO fix path from fixed
		var mgr = MicroscopeConfigManager.getInstance("F:\\QPScopeExtension\\smartpath_configurations\\config_PPM.yml");
		var missing = mgr.validateRequiredKeys(required);
		return missing.isEmpty();
	}

	/**
	 * Adds a menu item under the Extensions menu.
	 * When selected, it starts the microscope control workflow by delegating to QPScopeController.
	 *
	 * @param qupath The current QuPath GUI instance.
	 */

	private void addMenuItem(QuPathGUI qupath) {
		// Get or create the extension menu
		Menu extensionMenu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		// Create a submenu for our workflow options
		Menu workflowMenu = new Menu("Microscope Control Options");

		// Option 1: Bounding Box workflow
		MenuItem boundingBoxOption = new MenuItem("Start with Bounding Box");
		boundingBoxOption.setDisable(!configValid);
		boundingBoxOption.setOnAction(e -> QPScopeController.getInstance().startWorkflow("boundingBox"));

		// Option 2: Existing Image workflow. Disable if no image is open.
		MenuItem existingImageOption = new MenuItem("Start with Existing Image");
		existingImageOption.disableProperty().bind(
			Bindings.or(
				Bindings.createBooleanBinding(() -> qupath.getImageData() == null, qupath.imageDataProperty()),
				Bindings.createBooleanBinding(() -> !configValid,   qupath.imageDataProperty())
			)
		);
		existingImageOption.setOnAction(e -> QPScopeController.getInstance().startWorkflow("existingImage"));

		// Option 3: Test entry (for development only)
		MenuItem testEntryOption = new MenuItem("Test Entry");
		testEntryOption.setOnAction(e -> QPScopeController.getInstance().startWorkflow("test"));

		workflowMenu.getItems().addAll(boundingBoxOption, existingImageOption, testEntryOption);
		extensionMenu.getItems().add(workflowMenu);
		logger.info("Menu item added for extension: " + EXTENSION_NAME);
	}
}