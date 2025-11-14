# Integrating Project-Specific Logging into Workflows

This document provides step-by-step instructions for adding project-specific logging to QPSC workflows.

## Overview

Project-specific logging saves workflow logs directly in the QuPath project folder alongside acquired data. This makes troubleshooting and documentation easier by keeping logs with the data they describe.

## Quick Integration

### Step 1: Import ProjectLogger

Add the import to your workflow class:

```java
import qupath.ext.qpsc.utilities.ProjectLogger;
```

### Step 2: Wrap Workflow Execution

Wrap your workflow's main execution logic with `ProjectLogger.start()`:

```java
public void execute(Project<?> project, /* other params */) {
    // Enable project-specific logging
    try (ProjectLogger.Session session = ProjectLogger.start(project)) {

        // Check if logging was successfully enabled
        if (!session.isActive()) {
            logger.warn("Could not enable project logging, will use centralized log only");
        }

        // Your existing workflow code goes here
        runWorkflow();

    } // Logging automatically disabled here
}
```

That's it! All logger calls within the try block will now write to both:
1. Centralized log: `<QuPath>/logs/qpsc/qpsc-acquisition.log`
2. Project log: `<Project-Directory>/acquisition.log`

## Detailed Integration Examples

### Example 1: BoundingBoxWorkflow Integration

**Location**: `qupath/ext/qpsc/controller/BoundingBoxWorkflow.java`

**Current Structure** (simplified):
```java
public class BoundingBoxWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(BoundingBoxWorkflow.class);

    public void execute(Project<?> project, String sampleName, /* other params */) {
        logger.info("Starting bounding box workflow for sample: {}", sampleName);

        // Workflow steps
        createProject();
        performAcquisition();
        performStitching();
        importImage();

        logger.info("Bounding box workflow completed");
    }
}
```

**Integrated Version**:
```java
public class BoundingBoxWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(BoundingBoxWorkflow.class);

    public void execute(Project<?> project, String sampleName, /* other params */) {
        // Enable project-specific logging
        try (ProjectLogger.Session session = ProjectLogger.start(project)) {

            logger.info("Starting bounding box workflow for sample: {}", sampleName);
            logger.info("Project: {}", project.getPath());

            // Workflow steps (unchanged)
            createProject();
            performAcquisition();
            performStitching();
            importImage();

            logger.info("Bounding box workflow completed");

        } catch (Exception e) {
            logger.error("Bounding box workflow failed", e);
            throw e;
        }
    }
}
```

### Example 2: ExistingImageWorkflow Integration

**Location**: `qupath/ext/qpsc/controller/ExistingImageWorkflow.java`

```java
public class ExistingImageWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);

    public void execute(Project<?> project, ImageData<?> imageData, /* other params */) {
        // Enable project logging
        try (ProjectLogger.Session session = ProjectLogger.start(project)) {

            logger.info("Starting existing image workflow");
            logger.info("Image: {}", imageData.getServer().getMetadata().getName());

            // Workflow steps
            validateAnnotations();
            calculateCoordinates();
            performAcquisition();

            logger.info("Existing image workflow completed");

        } catch (Exception e) {
            logger.error("Existing image workflow failed", e);
            throw e;
        }
    }
}
```

### Example 3: MicroscopeAlignmentWorkflow Integration

**Location**: `qupath/ext/qpsc/controller/MicroscopeAlignmentWorkflow.java`

```java
public class MicroscopeAlignmentWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeAlignmentWorkflow.class);

    public void execute(Project<?> project, /* other params */) {
        // Enable project logging
        try (ProjectLogger.Session session = ProjectLogger.start(project)) {

            logger.info("Starting microscope alignment workflow");

            // Alignment steps
            captureReferenceImage();
            calculateAlignment();
            saveTransformation();

            logger.info("Microscope alignment workflow completed");
            logger.info("Alignment saved to project metadata");

        } catch (Exception e) {
            logger.error("Microscope alignment workflow failed", e);
            throw e;
        }
    }
}
```

### Example 4: BackgroundCollectionWorkflow Integration

**Location**: `qupath/ext/qpsc/controller/BackgroundCollectionWorkflow.java`

```java
public class BackgroundCollectionWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(BackgroundCollectionWorkflow.class);

    public void execute(String outputPath, /* other params */) {
        // Background collection doesn't always have a project
        // Use directory-based logging instead
        try (ProjectLogger.Session session = ProjectLogger.start(outputPath)) {

            logger.info("Starting background collection workflow");
            logger.info("Output: {}", outputPath);

            // Collection steps
            captureBackgroundImages();

            logger.info("Background collection completed");

        } catch (Exception e) {
            logger.error("Background collection failed", e);
            throw e;
        }
    }
}
```

## Integration Pattern for Asynchronous Workflows

Some workflows use `CompletableFuture` or background threads. In these cases, ensure the ProjectLogger session spans the entire async operation:

```java
public CompletableFuture<Void> executeAsync(Project<?> project) {
    return CompletableFuture.runAsync(() -> {
        // Enable project logging in the async thread
        try (ProjectLogger.Session session = ProjectLogger.start(project)) {

            logger.info("Starting async workflow");

            // Long-running operations
            performLongOperation();

            logger.info("Async workflow completed");

        } catch (Exception e) {
            logger.error("Async workflow failed", e);
            throw new RuntimeException(e);
        }
    });
}
```

## Integration with Progress Dialogs

When workflows show progress dialogs, the ProjectLogger can be enabled before the dialog is shown:

```java
public void execute(Project<?> project) {
    // Enable logging first
    try (ProjectLogger.Session session = ProjectLogger.start(project)) {

        logger.info("Starting workflow with progress dialog");

        // Show progress dialog
        Platform.runLater(() -> {
            DualProgressDialog dialog = new DualProgressDialog(/* params */);
            dialog.show();
        });

        // Perform workflow
        performSteps();

        logger.info("Workflow completed");

    } catch (Exception e) {
        logger.error("Workflow failed", e);
        throw e;
    }
}
```

## Handling Workflows Without Projects

Not all workflows have a QuPath project (e.g., test workflows, background collection). In these cases:

### Option 1: Use directory-based logging
```java
File outputDir = new File("/path/to/output");
try (ProjectLogger.Session session = ProjectLogger.start(outputDir)) {
    // Workflow code
}
```

### Option 2: Skip project logging
```java
if (project != null) {
    try (ProjectLogger.Session session = ProjectLogger.start(project)) {
        runWorkflow();
    }
} else {
    // Use centralized log only
    runWorkflow();
}
```

## Testing the Integration

After integration, verify project logging works:

1. **Run a workflow** with a valid QuPath project
2. **Check the project directory** for `acquisition.log`
3. **Verify the log contains** workflow events:
   ```
   2025-10-10 14:23:15.123 [JavaFX Application Thread] INFO  BoundingBoxWorkflow - Starting bounding box workflow for sample: Sample1
   2025-10-10 14:23:15.456 [JavaFX Application Thread] INFO  BoundingBoxWorkflow - Project: /path/to/project
   ```
4. **Check centralized log** also contains the same events
5. **Verify no errors** in console about logging configuration

## Troubleshooting Integration

### Log file not created in project directory

**Check**:
- Project path is valid and writable
- `ProjectLogger.start()` returned `isActive() == true`
- No errors in console about file permissions

**Solution**:
```java
try (ProjectLogger.Session session = ProjectLogger.start(project)) {
    if (!session.isActive()) {
        logger.warn("Project logging failed - check permissions on: {}", project.getPath());
    }
    // Continue anyway with centralized logging
}
```

### Logs missing after workflow completes

**Check**:
- `ProjectLogger.Session` is closed (automatic with try-with-resources)
- No exceptions thrown before session closes
- Session scope covers entire workflow

**Solution**: Always use try-with-resources to guarantee cleanup:
```java
try (ProjectLogger.Session session = ProjectLogger.start(project)) {
    // Workflow code
} // Guaranteed to close even if exception thrown
```

### Multiple workflows overwriting logs

**Expected behavior**: If multiple workflows run on the same project, they append to the same log file. This is intentional - all workflows for a project share one log.

**To separate workflows**: Add workflow identifiers to log messages:
```java
logger.info("[BoundingBox] Starting workflow");
logger.info("[ExistingImage] Starting workflow");
```

## Best Practices

1. **Always use try-with-resources** for automatic cleanup
2. **Check `session.isActive()`** and log a warning if it fails
3. **Enable logging early** before any significant workflow operations
4. **Don't disable manually** - let try-with-resources handle it
5. **Add workflow context** to log messages (sample name, image name, etc.)
6. **Log workflow completion** so users know when acquisition finished
7. **Log errors with full stack traces** for troubleshooting

## Summary

Project-specific logging requires only a few lines of code:

```java
try (ProjectLogger.Session session = ProjectLogger.start(project)) {
    // Your existing workflow code here
}
```

All existing logger calls automatically write to both centralized and project-specific logs. No other code changes needed!
