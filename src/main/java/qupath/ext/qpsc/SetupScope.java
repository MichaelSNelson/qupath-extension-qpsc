package qupath.ext.qpsc;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.Property;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.InterfaceController;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.IOException;
import java.util.ResourceBundle;


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
		AddQPPreferences.getInstance();
		// Ensure UI changes occur on the JavaFX Application Thread.
		Platform.runLater(() -> addMenuItem(qupath));
	}

	/**
	 * Adds a menu item under the Extensions menu.
	 * When selected, it starts the microscope control workflow by delegating to QPScopeController.
	 *
	 * @param qupath The current QuPath GUI instance.
	 */
//	private void addMenuItem(QuPathGUI qupath) {
//		// Retrieve or create the menu under "Extensions > [Extension Name]"
//		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
//		var menuItem = new javafx.scene.control.MenuItem("Launch " + EXTENSION_NAME);
//		menuItem.setOnAction(e -> {
//			if (QPScopeChecks.checkEnvironment()) {
//				// Delegate to the workflow controller.
//				QPScopeController.getInstance().startWorkflow();
//			}
//
//		});
//		menu.getItems().add(menuItem);
//
//	}
	private void addMenuItem(QuPathGUI qupath) {
		// Get or create the extension menu
		Menu extensionMenu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		// Create a submenu for our workflow options
		Menu workflowMenu = new Menu("Microscope Control Options");

		// Option 1: Bounding Box workflow
		MenuItem boundingBoxOption = new MenuItem("Start with Bounding Box");
		boundingBoxOption.setOnAction(e -> QPScopeController.getInstance().startWorkflow("boundingBox"));

		// Option 2: Existing Image workflow. Disable if no image is open.
		MenuItem existingImageOption = new MenuItem("Start with Existing Image");
		existingImageOption.setOnAction(e -> QPScopeController.getInstance().startWorkflow("existingImage"));
		existingImageOption.disableProperty().bind(
				Bindings.createBooleanBinding(() -> qupath.getImageData() == null, qupath.imageDataProperty())
		);

		// Option 3: Test entry (for development only)
		MenuItem testEntryOption = new MenuItem("Test Entry");
		testEntryOption.setOnAction(e -> QPScopeController.getInstance().startWorkflow("test"));

		workflowMenu.getItems().addAll(boundingBoxOption, existingImageOption, testEntryOption);
		extensionMenu.getItems().add(workflowMenu);
		logger.info("Menu item added for extension: " + EXTENSION_NAME);
	}
}