Here's the comprehensive class architecture diagram with all methods and classes:

```mermaid
classDiagram
    %% External Interfaces
    class QuPathExtension {
        <<interface>>
        +installExtension(QuPathGUI)
        +getName() String
        +getDescription() String
        +getQuPathVersion() Version
    }
    class GitHubProject {
        <<interface>>
        +getRepository() GitHubRepo
    }
    class AutoCloseable {
        <<interface>>
        +close()
    }
    class VBox {
        <<JavaFX>>
    }

    %% ========== MAIN PACKAGE ==========
    class SetupScope {
        -Logger logger
        -ResourceBundle res
        -String EXTENSION_NAME
        -String EXTENSION_DESCRIPTION
        -Version EXTENSION_QUPATH_VERSION
        -GitHubRepo EXTENSION_REPOSITORY
        -boolean configValid
        +getName() String
        +getDescription() String
        +getQuPathVersion() Version
        +getRepository() GitHubRepo
        +installExtension(QuPathGUI)
        -addMenuItem(QuPathGUI)
    }
    SetupScope ..|> QuPathExtension
    SetupScope ..|> GitHubProject

    class QPScopeChecks {
        +checkEnvironment() boolean
        +validateMicroscopeConfig() boolean
        -checkHardwareAccessible() boolean
        -checkQuPathState() boolean
    }

    %% ========== CONTROLLER PACKAGE ==========
    class QPScopeController {
        -QPScopeController instance
        +getInstance() QPScopeController
        +init()
        -startUserInteraction()
        -showUserDialog(String, String) CompletableFuture~Void~
        +startWorkflow(String)
    }

    class MicroscopeController {
        -Logger logger
        -MicroscopeSocketClient socketClient
        -AffineTransform currentTransform
        -MicroscopeController instance
        -MicroscopeController()
        +getInstance() MicroscopeController
        +getStagePositionXY() double[]
        +getStagePositionZ() double
        +getStagePositionR() double
        +moveStageXY(double, double)
        +moveStageZ(double)
        +moveStageR(double)
        +startAcquisition(AcquisitionCommandBuilder)
        +onMoveButtonClicked(PathObject)
        +setCurrentTransform(AffineTransform)
        +isWithinBoundsXY(double, double) boolean
        +isWithinBoundsZ(double) boolean
    }

    class BoundingBoxWorkflow {
        -Logger logger
        -ExecutorService STITCH_EXECUTOR
        +run()
    }

    class ExistingImageWorkflow {
        -Logger logger
        -ExecutorService STITCH_EXECUTOR
        +run()
        -checkForSlideSpecificAlignment() CompletableFuture~AffineTransform~
        -handleExistingSlideAlignment() CompletableFuture~Void~
        -continueWithAlignmentSelection() CompletableFuture~Void~
        -handleExistingAlignmentPath() CompletableFuture~Void~
        -handleManualAlignmentPath() CompletableFuture~Void~
        -setupProject() CompletableFuture~ProjectInfo~
        -processExistingAlignment() CompletableFuture~Void~
        -proceedWithAlignmentRefinement() CompletableFuture~Void~
        -processManualAlignment() CompletableFuture~Void~
        -ensureAnnotationsExist() List~PathObject~
        -promptForTissueDetectionScript() String
        -getCurrentValidAnnotations() List~PathObject~
        -ensureAnnotationNames(List~PathObject~)
        -createTilesForAnnotations()
        -deleteAllTiles(QuPathGUI, String)
        -performSingleTileRefinement() CompletableFuture~AffineTransform~
        -offerToSaveTransform() CompletableFuture~Boolean~
        -continueToAcquisition() CompletableFuture~Void~
        -ensureAnnotationsReadyForAcquisition() List~PathObject~
        -cleanupStaleFolders(String, List~PathObject~)
        -deleteDirectoryRecursively(File)
        -processAnnotationsForAcquisition() CompletableFuture~Void~
        -performAnnotationAcquisition() CompletableFuture~Boolean~
        -performAnnotationStitching() CompletableFuture~Void~
        -stitchAnnotation() CompletableFuture~Void~
        -stitchAnnotationAngle() CompletableFuture~Void~
    }

    class ExistingImageWorkflow_WorkflowContext {
        +SampleSetupResult sample
        +String modality
        +WorkflowContext(SampleSetupResult, String)
    }

    class ExistingImageWorkflow_AlignmentContext {
        +WorkflowContext context
        +double pixelSize
        +ProjectInfo projectInfo
        +AffineTransform transform
        +AlignmentContext(WorkflowContext, double, ProjectInfo, AffineTransform)
    }

    class ExistingImageWorkflow_ManualAlignmentContext {
        +WorkflowContext context
        +double pixelSize
        +ProjectInfo projectInfo
        +ManualAlignmentContext(WorkflowContext, double, ProjectInfo)
    }

    class ExistingImageWorkflow_ProjectInfo {
        +String projectPath
        +String imagingModeWithIndex
        +String tempTileDirectory
    }

    class MicroscopeAlignmentWorkflow {
        -Logger logger
        +run()
        -performDetection() MacroImageResults
        -processAlignmentWithProject()
        -createAlignmentTiles()
        -createTilesForAnnotations()
        -saveGeneralTransform()
        -validateTransformWithTestPoints()
        -runTissueDetectionScript(QuPathGUI)
    }

    class MicroscopeAlignmentWorkflow_CombinedConfig {
        +MicroscopeSelection microscopeSelection
        +SampleSetupResult sampleSetup
        +AlignmentConfig alignConfig
    }

    class MicroscopeAlignmentWorkflow_MacroImageResults {
        +BufferedImage macroImage
        +ROI detectedBox
        +double confidence
        +AffineTransform initialTransform
        +double pixelSize
    }

    class TestWorkflow {
        +run()
        -runTests(QuPathGUI)
        -formatTransform(String, AffineTransform) String
        -showResults(String)
        -showError(String)
    }

    %% ========== UI PACKAGE ==========
    class InterfaceController {
        -TextField sampleNameField
        -TextField pixelSizeField
        +createAndShow() CompletableFuture~UserInputResult~
        +initialize()
        +onOkClicked()
        +onCancelClicked()
    }
    InterfaceController --|> VBox

    class InterfaceController_UserInputResult {
        +String sampleName
        +Double pixelSize
        +UserInputResult(String, Double)
        +toString() String
    }

    class AffineTransformationController {
        +setupAffineTransformationAndValidationGUI() CompletableFuture~AffineTransform~
    }

    class AlignmentSelectionController {
        +showDialog(QuPathGUI, String) CompletableFuture~AlignmentChoice~
        -updateTransformInfoDisplay(TextArea, AffineTransform)
    }

    class AlignmentSelectionController_AlignmentChoice {
        +boolean useExisting
        +AffineTransform selectedTransform
        +boolean refineTransform
        +String newTransformName
    }

    class BoundingBoxController {
        +showDialog() CompletableFuture~BoundingBoxResult~
    }

    class BoundingBoxController_BoundingBoxResult {
        +String sampleName
        +String projectsFolder
        +BoundingBox boundingBox
        +String modality
        +BoundingBoxResult(String, String, BoundingBox, String)
    }

    class ExistingImageController {
        +showDialog() CompletableFuture~UserInput~
        +requestPixelSizeDialog(double) CompletableFuture~Double~
        +promptForAnnotations()
    }

    class ExistingImageController_UserInput {
        +String modality
        +boolean createNewProject
        +UserInput(String, boolean)
    }

    class GreenBoxPreviewController {
        +showPreviewDialog() CompletableFuture~DetectionResult~
    }

    class MacroImageController {
        +showAlignmentDialog() CompletableFuture~AlignmentConfig~
        -createGreenBoxTab(QuPathGUI) Tab
        -createTransformTab() Tab
        -createThresholdTab(QuPathGUI) Tab
        -generateTransformName(String) String
        -updateThresholdParameterVisibility()
    }

    class MacroImageController_AlignmentConfig {
        +DetectionParams greenBoxParams
        +AffineTransform selectedTransform
        +String transformName
        +boolean saveNewTransform
        +ThresholdMethod thresholdMethod
        +Map~String,Object~ thresholdParams
        +AlignmentConfig(DetectionParams, AffineTransform, String, boolean, ThresholdMethod, Map)
    }

    class MicroscopeSelectionDialog {
        +showDialog() CompletableFuture~MicroscopeSelection~
    }

    class MicroscopeSelectionDialog_MicroscopeSelection {
        +String microscopeName
        +String scannerName
        +MicroscopeSelection(String, String)
    }

    class PPMAngleSelectionController {
        +showDialog(double, double) CompletableFuture~AngleExposureResult~
        -isValidPositiveInteger(String) boolean
    }

    class PPMAngleSelectionController_AngleExposureResult {
        -List~AngleExposure~ angleExposures
        +getAngles() List~Double~
    }

    class PPMAngleSelectionController_AngleExposure {
        +double angle
        +int exposureMs
        +AngleExposure(double, int)
        +toString() String
    }

    class SampleSetupController {
        +showDialog() CompletableFuture~SampleSetupResult~
    }

    class ServerConnectionController {
        -Dialog~Void~ dialog
        -TabPane tabPane
        -TextField hostField
        -TextField portField
        -TextArea statusArea
        +showDialog() CompletableFuture~Void~
        -show() CompletableFuture~Void~
        -createDialog()
        -createConnectionTab() Tab
        -createAdvancedTab() Tab
        -createStatusTab() Tab
        -loadSettings()
        -saveSettings()
        -resetToDefaults()
        -testConnection()
        -connectNow()
        -updateStatusDisplay()
        -logMessage(String)
        -setControlsEnabled(boolean)
        -cleanup()
    }

    class StageMovementController {
        +showTestStageMovementDialog()
    }

    class TestWorkFlowController {
        +showDialog() CompletableFuture~Void~
        -startHeartbeatServer(CompletableFuture~Void~)
        -launchPythonHeartbeatClient()
    }

    class UIFunctions {
        +showProgressBarAsync() ProgressHandle
        +notifyUserOfError(String, String)
        +checkValidAnnotationsGUI(List~String~, List~String~, String)
        +stageToQuPathAlignmentGUI2() boolean
        +showAlertDialog(String)
        +promptTileSelectionDialogAsync(String) CompletableFuture~PathObject~
        +promptYesNoDialog(String, String) boolean
    }

    class UIFunctions_ProgressHandle {
        -Stage stage
        -Timeline timeline
        -Runnable cancelCallback
        +ProgressHandle(Stage, Timeline)
        +close()
        +setCancelCallback(Runnable)
        +triggerCancel()
    }

    %% ========== MODEL PACKAGE ==========
    class RotationStrategy {
        <<interface>>
        +getRotationTicks(String) CompletableFuture~List~Double~~
        +getRotationTicksWithExposure(String) CompletableFuture~List~TickExposure~~
        +getAngleSuffix(String, double) String
        +requiresExposureInput() boolean
    }

    class BrightfieldRotationStrategy {
        +getRotationTicks(String) CompletableFuture~List~Double~~
        +getRotationTicksWithExposure(String) CompletableFuture~List~TickExposure~~
        +getAngleSuffix(String, double) String
        +requiresExposureInput() boolean
    }
    BrightfieldRotationStrategy ..|> RotationStrategy

    class PPMRotationStrategy {
        -TickExposure plusAngleExposure
        -TickExposure minusAngleExposure
        -TickExposure zeroAngleExposure
        -TickExposure brightfieldExposure
        +PPMRotationStrategy(TickExposure, TickExposure, TickExposure, TickExposure)
        +getRotationTicks(String) CompletableFuture~List~Double~~
        +getRotationTicksWithExposure(String) CompletableFuture~List~TickExposure~~
        +getAngleSuffix(String, double) String
        +requiresExposureInput() boolean
    }
    PPMRotationStrategy ..|> RotationStrategy

    class NoRotationStrategy {
        +getRotationTicks(String) CompletableFuture~List~Double~~
        +getRotationTicksWithExposure(String) CompletableFuture~List~TickExposure~~
        +getAngleSuffix(String, double) String
        +requiresExposureInput() boolean
    }
    NoRotationStrategy ..|> RotationStrategy

    class RotationManager {
        -Map~String,RotationStrategy~ strategies
        -initializeStrategies(String)
        +getRotationTicks(String) CompletableFuture~List~Double~~
        +getRotationTicksWithExposure(String) CompletableFuture~List~TickExposure~~
        +getAngleSuffix(String, double) String
    }

    class RotationManager_TickExposure {
        +double ticks
        +int exposureMs
        +TickExposure(double, int)
        +toString() String
    }

    RotationManager --> RotationStrategy

    %% ========== SERVICE PACKAGE ==========
    class AcquisitionCommandBuilder {
        -String yamlPath
        -String projectsFolder
        -String sampleLabel
        -String scanType
        -String regionName
        -List~TickExposure~ angleExposures
        -double laserPower
        -int laserWavelength
        -double dwellTime
        -int averaging
        -boolean zStackEnabled
        -double zStart
        -double zEnd
        -double zStep
        +yamlPath(String) AcquisitionCommandBuilder
        +projectsFolder(String) AcquisitionCommandBuilder
        +sampleLabel(String) AcquisitionCommandBuilder
        +scanType(String) AcquisitionCommandBuilder
        +regionName(String) AcquisitionCommandBuilder
        +angleExposures(List~TickExposure~) AcquisitionCommandBuilder
        +laserPower(double) AcquisitionCommandBuilder
        +laserWavelength(int) AcquisitionCommandBuilder
        +dwellTime(double) AcquisitionCommandBuilder
        +averaging(int) AcquisitionCommandBuilder
        +enableZStack(double, double, double) AcquisitionCommandBuilder
        -validate()
        +buildSocketMessage() String
        +brightfieldBuilder() AcquisitionCommandBuilder
        +ppmBuilder() AcquisitionCommandBuilder
        +laserScanningBuilder() AcquisitionCommandBuilder
    }

    class MicroscopeSocketClient {
        -Logger logger
        -String host
        -int port
        -int connectTimeout
        -int readTimeout
        -int maxReconnectAttempts
        -long reconnectDelayMs
        -Socket socket
        -DataInputStream inputStream
        -DataOutputStream outputStream
        -boolean connected
        -AtomicInteger reconnectAttempts
        -ScheduledExecutorService reconnectExecutor
        -ScheduledExecutorService healthCheckExecutor
        -volatile boolean shutdown
        +MicroscopeSocketClient(String, int, int, int, int, int, long)
        +connect()
        +disconnect()
        +getStageXY() double[]
        +getStageZ() double
        +getStageR() double
        +moveStageXY(double, double)
        +moveStageZ(double)
        +moveStageR(double)
        +startAcquisition(AcquisitionCommandBuilder)
        -executeCommand(Command, byte[], int) byte[]
        -ensureConnected()
        -handleIOException(IOException)
        -scheduleReconnection()
        -startHealthMonitoring()
        -cleanup()
        +shutdownServer()
        +close()
        +getAcquisitionStatus() AcquisitionState
        +getAcquisitionProgress() AcquisitionProgress
        +cancelAcquisition() boolean
        +monitorAcquisition() AcquisitionState
    }
    MicroscopeSocketClient ..|> AutoCloseable

    class MicroscopeSocketClient_Command {
        <<enumeration>>
        GET_XY
        GET_Z
        GET_R
        MOVE_XY
        MOVE_Z
        MOVE_R
        ACQUIRE
        STATUS
        PROGRESS
        CANCEL
        SHUTDOWN
        -String value
        Command(String)
    }

    class MicroscopeSocketClient_AcquisitionState {
        <<enumeration>>
        IDLE
        RUNNING
        COMPLETED
        CANCELLED
        FAILED
        +fromString(String) AcquisitionState
    }

    class MicroscopeSocketClient_AcquisitionProgress {
        +int current
        +int total
        +AcquisitionProgress(int, int)
        +getPercentage() double
        +toString() String
    }

    %% ========== PREFERENCES PACKAGE ==========
    class PersistentPreferences {
        %% This class appears to be empty or contains only static methods
    }

    class QPPreferenceDialog {
        -StringProperty microscopeConfigFileProperty
        -StringProperty projectsFolderProperty
        -StringProperty extensionLocationProperty
        -StringProperty tissueDetectionScriptProperty
        -StringProperty scannerProperty
        -ObjectProperty~CompressionType~ compressionTypeProperty
        -StringProperty cliFolderProperty
        +installPreferences(QuPathGUI)
        +getMicroscopeConfigFileProperty() StringProperty
        +getProjectsFolderProperty() StringProperty
        +getExtensionLocationProperty() StringProperty
        +getTissueDetectionScriptProperty() StringProperty
        +getScannerProperty() StringProperty
        +getCompressionTypeProperty() ObjectProperty~CompressionType~
        +getCliFolderProperty() StringProperty
        -getScannerChoices() ObservableList~String~
        -getAvailableTransforms() List~String~
    }

    %% ========== UTILITIES PACKAGE ==========
    class AffineTransformManager {
        -String configDirectory
        -Map~String,TransformPreset~ transforms
        -Gson gson
        +AffineTransformManager(String)
        -loadTransforms() Map~String,TransformPreset~
        -saveTransforms()
        +savePreset(TransformPreset)
        -persistTransforms()
        +getTransformsForMicroscope(String) List~TransformPreset~
        +deleteTransform(String) boolean
        +validateTransform(AffineTransform, double, double) boolean
        +loadSavedTransformFromPreferences() AffineTransform
        +hasSavedTransform() boolean
        +saveSlideAlignment()
        +loadSlideAlignment() AffineTransform
        +loadSlideAlignmentFromDirectory(File, String) AffineTransform
    }

    class AffineTransformManager_TransformPreset {
        +String name
        +String microscope
        +String mountingMethod
        +AffineTransform transform
        +long timestamp
        +TransformPreset(String, String, String, AffineTransform, long)
        +TransformPreset(String, String, String, AffineTransform)
        +toString() String
    }

    class BoundingBox {
        +double x1
        +double y1
        +double x2
        +double y2
        +BoundingBox(double, double, double, double)
    }

    class GreenBoxDetector {
        +detectGreenBox(BufferedImage, DetectionParams) DetectionResult
        -createGreenMask(BufferedImage, DetectionParams) BufferedImage
        -isGreenBoxPixel(int, DetectionParams) boolean
        -findBoxEdges(BufferedImage, DetectionParams) List~Rectangle~
        -isHorizontalEdge(BufferedImage, int, int, DetectionParams) boolean
        -isVerticalEdge(BufferedImage, int, int, DetectionParams) boolean
        -findCompleteBox(List~Rectangle~, int, int, DetectionParams) ROI
        -findLargestGreenRegion(List~Rectangle~, int, int, DetectionParams) ROI
        -calculateConfidence(ROI, BufferedImage, DetectionParams) double
        -createDebugImage(BufferedImage, BufferedImage, ROI) BufferedImage
        +calculateInitialTransform() AffineTransform
    }

    class GreenBoxDetector_DetectionParams {
        +double greenThreshold
        +double saturationMin
        +double brightnessMin
        +double brightnessMax
        +int edgeThickness
        +int minWidth
        +int minHeight
        +DetectionParams()
        +DetectionParams(double, double, double, double, int, int, int)
        -loadDefaults()
        +saveToPreferences()
    }

    class GreenBoxDetector_DetectionResult {
        +ROI detectedBox
        +BufferedImage debugImage
        +double confidence
        +DetectionResult(ROI, BufferedImage, double)
    }

    class MacroImageAnalyzer {
        +analyzeMacroImage() MacroAnalysisResult
        -calculateThreshold(BufferedImage, ThresholdMethod, Map) int
        -convertToGrayscale(BufferedImage) BufferedImage
        -calculateOtsuThreshold(int[]) int
        -calculateMeanThreshold(int[]) int
        -calculatePercentileThreshold(int[], double) int
        -applyThreshold(BufferedImage, int) BufferedImage
        -applyColorThreshold(BufferedImage, Map) BufferedImage
        -detectEosin(int, double, double, double, double) boolean
        -detectHematoxylin(int, double, double, double, double) boolean
        -detectByColorDeconvolution(int, double, double) boolean
        -findTissueRegions(BufferedImage, int) List~ROI~
        -floodFill(BufferedImage, boolean[][], int, int) Rectangle
        -computeBoundingBox(List~ROI~) ROI
        +saveAnalysisImages(MacroAnalysisResult, String)
        -debugBioFormatsAssociatedImages(ImageServer)
    }

    class MacroImageAnalyzer_ThresholdMethod {
        <<enumeration>>
        OTSU
        MEAN
        PERCENTILE
        FIXED
        COLOR_DECONVOLUTION
        +toString() String
    }

    class MacroImageAnalyzer_MacroAnalysisResult {
        +BufferedImage macroImage
        +BufferedImage thresholdedImage
        +List~ROI~ tissueRegions
        +ROI boundingBox
        +double macroPixelSize
        +double scaleFactor
        +MacroAnalysisResult(BufferedImage, BufferedImage, List~ROI~, ROI, double, double)
        +scaleToMainImage(ROI) ROI
    }

    class MacroImageUtility {
        +cropToSlideArea(BufferedImage) CroppedMacroResult
        +cropToSlideArea(BufferedImage, double, double) CroppedMacroResult
        +cropToSlideArea(BufferedImage, String) CroppedMacroResult
        +getMacroPixelSize(String) double
        +getMacroPixelSize() double
        +flipMacroImage(BufferedImage, boolean, boolean) BufferedImage
        +retrieveMacroImage(QuPathGUI) BufferedImage
        +isMacroImageAvailable(QuPathGUI) boolean
    }

    class MacroImageUtility_CroppedMacroResult {
        +BufferedImage croppedImage
        +double offsetX
        +double offsetY
        +double scaleX
        +double scaleY
        +CroppedMacroResult(BufferedImage, double, double, double, double)
        +adjustROI(ROI) ROI
        +toOriginalCoordinates(double, double) double[]
    }

    class MicroscopeConfigManager {
        -String configPath
        -String resourcePath
        -Map~String,Object~ configData
        -Map~String,Object~ resourceData
        -MicroscopeConfigManager instance
        -MicroscopeConfigManager(String)
        +getInstance(String) MicroscopeConfigManager
        +reload(String)
        -computeResourcePath(String) String
        +getString(String...) String
        +getInteger(String...) Integer
        +getDouble(String...) Double
        +getPPMConfig() Map~String,Object~
        +getBoolean(String...) Boolean
        +validateRequiredKeys(Set~String[]~) Set~String[]~
        +writeMetadataAsJson(Map~String,Object~, Path)
        +getAvailableScanners() List~String~
        +getScannerType(String) String
        +scannerRequiresCropping(String) boolean
        +getScannerMacroPixelSize(String) double
        +getScannerSlideBounds(String) SlideBounds
    }

    class MicroscopeConfigManager_SlideBounds {
        +int xMin
        +int xMax
        +int yMin
        +int yMax
        +SlideBounds(int, int, int, int)
        +toString() String
    }

    class MinorFunctions {
        +isWindows() boolean
        +countTifEntriesInTileConfig(List~String~) int
        +calculateScriptPaths(String) Map~String,String~
        +getUniqueFolderName(String) String
        +extractFilePath(String) String
        +writeTileExtremesToFile()
        +readTileExtremesFromFile(String) double[][]
        +firstLines(String, int) String
        +getYamlString(Map~String,Object~, String...) String
        +getYamlDouble(Map~String,Object~, String...) Double
        +getYamlBoolean(Map~String,Object~, String...) Boolean
        +getYamlInteger(Map~String,Object~, String...) Integer
    }

    class QPProjectFunctions {
        +createAndOpenQuPathProject() Map~String,Object~
        -findImageInProject() ProjectImageEntry~BufferedImage~
        -extractImagePath(ImageData~BufferedImage~) String
        -importImageToProject() ProjectImageEntry~BufferedImage~
        -prepareProjectFolders() ProjectSetup
        -createOrLoadProject() Project~BufferedImage~
        +getCurrentProjectInformation() Map~String,Object~
        +addImageToProject() boolean
        +createProject(String, String) Project~BufferedImage~
        +onImageLoadedInViewer(QuPathGUI, String, Runnable)
        +saveCurrentImageData()
    }

    class QPProjectFunctions_ProjectSetup {
        +String imagingModeWithIndex
        +String tempTileDirectory
        ProjectSetup(String, String)
    }

    class TilingRequest {
        +String outputFolder
        +String modalityName
        +double frameWidth
        +double frameHeight
        +double overlapPercent
        +boolean invertX
        +boolean invertY
        +boolean createDetections
        +boolean addBuffer
        +BoundingBox boundingBox
        +List~PathObject~ annotations
    }

    class TilingRequest_Builder {
        +outputFolder(String) Builder
        +modalityName(String) Builder
        +frameSize(double, double) Builder
        +overlapPercent(double) Builder
        +invertAxes(boolean, boolean) Builder
        +createDetections(boolean) Builder
        +addBuffer(boolean) Builder
        +boundingBox(double, double, double, double) Builder
        +annotations(List~PathObject~) Builder
        +build() TilingRequest
    }

    class TilingUtilities {
        +createTiles(TilingRequest)
        -createBoundingBoxTiles(TilingRequest)
        -createAnnotationTiles(TilingRequest)
        -createTileGrid()
    }

    class TransformationFunctions {
        +transformQuPathFullResToStage() double[]
        +transformMacroOriginalToStage() double[]
        +transformMacroFlippedToStage() double[]
        +transformStageToQuPathFullRes() double[]
        +calculateMacroOriginalToFullResTransform() AffineTransform
        +calculateMacroFlippedToFullResTransform() AffineTransform
        +calculateFullResToStageTransform() AffineTransform
        +createGeneralMacroToStageTransform() AffineTransform
        +validateTransform() boolean
        +validateTransformWithGroundTruth() boolean
        +applyFlipsToCoordinates() double[]
        +reverseFlipsOnCoordinates() double[]
        +applyFlipsToROI() double[]
        +logTransformDetails(String, AffineTransform)
        -describeTransformType(int) String
        +transformTileConfiguration() List~String~
        -processTileConfigurationFile()
        +findImageBoundaries(File) List~List~Double~~
        +addTranslationToScaledAffine() AffineTransform
        +getTopCenterTile(Collection~PathObject~) PathObject
        +getLeftCenterTile(Collection~PathObject~) PathObject
        +setupAffineTransformation() AffineTransform
    }

    class UtilityFunctions {
        +stitchImagesAndUpdateProject() String
        +execCommand(String...) int
        +deleteTilesAndFolder(String)
        +zipTilesAndMove(String)
        +modifyTissueDetectScript() String
    }

    %% ========== TEST PACKAGE ==========
    class CoordinateTransformationTest {
        %% Test methods not detailed for brevity
    }

    class MicroscopeSocketClientTest {
        -MockMicroscopeServer mockServer
        -MicroscopeSocketClient client
        +setUp()
        +tearDown()
        +testConnection()
        +testGetStageXY()
        +testGetStageZ()
        +testGetStageR()
        +testMoveStageXY()
        +testMoveStageZ()
        +testMoveStageR()
        +testStart