package qupath.ext.qpsc;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.QPScopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
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
 *   - It registers a menu item in QuPath’s Extensions menu.
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

		// 1) Start with a bounding box workflow
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
		// 3) Macro image workflow (only enabled if image has macro)
		MenuItem macroImageOption = new MenuItem(res.getString("menu.macroimage"));
		macroImageOption.disableProperty().bind(
				Bindings.or(
						// no image open?
						Bindings.createBooleanBinding(
								() -> qupath.getImageData() == null,
								qupath.imageDataProperty()
						),
						// or no macro image?
						Bindings.createBooleanBinding(
								() -> {
									var data = qupath.getImageData();
									if (data == null) return true;
									var server = data.getServer();
									return server == null ||
											!server.getAssociatedImageList().contains("macro");
								},
								qupath.imageDataProperty()
						)

                )
		);
		macroImageOption.setOnAction(e -> {
			try {
				QPScopeController.getInstance().startWorkflow("macroImage");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});
		// 3) Basic stage control (test only)
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
		// 4) Testing #TODO remove before release
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

		// Add them straight to the QP Scope menu
		extensionMenu.getItems().addAll(
				boundingBoxOption,
				existingImageOption,
				macroImageOption,  // Add this line
				stageControlOption,
				testOption
		);

		logger.info("Menu items added for extension: " + EXTENSION_NAME);
	}
}