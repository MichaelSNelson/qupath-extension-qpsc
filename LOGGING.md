# QPSC Logging Configuration

The QPSC extension uses SLF4J with Logback for comprehensive logging of acquisition workflows, microscope communication, and system events.

## Log File Locations

Logs are saved to multiple locations depending on configuration:

### Centralized Logs (Default)
```
<QuPath-Directory>/logs/qpsc/
├── qpsc-acquisition.log           # Current filtered acquisition log
├── qpsc-acquisition-2025-10-09.log  # Previous days (kept for 7 days)
└── qpsc-acquisition-2025-10-08.log
```

### Project-Specific Logs (Per-Workflow)
When enabled by workflows, logs are also saved alongside the project data:
```
<QuPath-Project-Directory>/
├── project.qpproj
├── data/
├── acquisition.log                 # Workflow-specific filtered log
└── ... (other project files)
```

## Log Files

### 1. Centralized Acquisition Log (qpsc-acquisition.log)

**Location**: `<QuPath-Directory>/logs/qpsc/qpsc-acquisition.log`

**Purpose**: Centralized log of all acquisition workflows without verbose heartbeat messages

**Contains**:
- Acquisition start/stop events
- Coordinate transformations
- Stitching operations
- Error and warning messages
- Major workflow events

**Excludes**:
- Stage XY position heartbeats
- Health check messages
- Low-level socket debug messages
- Repetitive connection status updates
- **File progress updates** ("PROGRESS UPDATE: X of Y files")
- **Stitching directory listings** (verbose directory contents during multi-angle processing)
- **.tif file counting messages** ("Found X .tif entries", "X OME-TIFF files")
- **Percentage completion messages** during long-running operations

**Retention**: 7 days, max 100MB total

### 2. Project-Specific Log (acquisition.log)

**Location**: `<QuPath-Project-Directory>/acquisition.log`

**Purpose**: Workflow-specific log saved alongside the acquired data in the QuPath project folder

**Activation**: Enabled programmatically by workflows using `ProjectLogger` utility

**Contains**:
- All workflow events for a specific acquisition
- Same filtered content as centralized log (no heartbeat spam)
- Coordinate transformations and tiling calculations
- Acquisition progress and completion status
- Any errors or warnings during the workflow

**Benefits**:
- **Data Organization**: Log stays with the data it describes
- **Easy Access**: No need to search centralized logs by timestamp
- **Project Portability**: Logs move with the project if copied/shared
- **Troubleshooting**: Clear record of acquisition parameters and events

**Retention**: Permanent (not automatically deleted)

**Usage in Code**:
```java
// Using try-with-resources (recommended)
try (ProjectLogger.Session session = ProjectLogger.start(project)) {
    logger.info("Starting acquisition for {}", sampleName);
    // ... workflow code ...
} // Automatically cleaned up

// Or manual enable/disable
ProjectLogger.enable(project);
try {
    logger.info("Starting acquisition...");
    // ... workflow code ...
} finally {
    ProjectLogger.disable();
}
```

### 3. Debug Log (qpsc-debug.log) - Optional

**Purpose**: Full debug log including all heartbeat and health check messages

**Status**: Disabled by default (uncomment in `logback.xml` to enable)

**Retention**: 3 days, max 200MB total

## Customizing the Logging

The logging configuration is in: `src/main/resources/logback.xml`

### Change Log Level

To see more detailed messages (DEBUG level):

```xml
<logger name="qupath.ext.qpsc" level="DEBUG" additivity="false">
```

To reduce verbosity (only WARN and ERROR):

```xml
<logger name="qupath.ext.qpsc" level="WARN" additivity="false">
```

### Enable Full Debug Log

Uncomment the `DEBUG_LOG` appender and reference in `logback.xml`:

```xml
<!-- Uncomment this entire block -->
<appender name="DEBUG_LOG" class="ch.qos.logback.core.rolling.RollingFileAppender">
    ...
</appender>

<!-- And add this reference -->
<logger name="qupath.ext.qpsc" level="DEBUG" additivity="false">
    <appender-ref ref="ACQUISITION_LOG" />
    <appender-ref ref="DEBUG_LOG" />  <!-- Add this line -->
    <appender-ref ref="CONSOLE" />
</logger>
```

### Add Custom Filtering

To exclude additional message patterns, add to the filter expression:

```xml
<filter class="ch.qos.logback.core.filter.EvaluatorFilter">
    <evaluator>
        <expression>
            return message.contains("Stage XY position: (") ||
                   message.contains("Health check passed") ||
                   message.contains("Your custom pattern here");
        </expression>
    </evaluator>
    <OnMatch>DENY</OnMatch>
    <OnMismatch>NEUTRAL</OnMismatch>
</filter>
```

### Control Specific Components

Adjust logging for individual classes:

```xml
<!-- Reduce MicroscopeSocketClient verbosity -->
<logger name="qupath.ext.qpsc.service.microscope.MicroscopeSocketClient" level="WARN" />

<!-- Enable detailed tiling debug messages -->
<logger name="qupath.ext.qpsc.utilities.TilingUtilities" level="DEBUG" />

<!-- Disable test workflow messages entirely -->
<logger name="qupath.ext.qpsc.ui.TestWorkFlowController" level="OFF" />
```

### Change Log Retention

Modify the rolling policy:

```xml
<rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
    <fileNamePattern>${LOG_DIR}/qpsc-acquisition-%d{yyyy-MM-dd}.log</fileNamePattern>
    <maxHistory>30</maxHistory>        <!-- Keep 30 days instead of 7 -->
    <totalSizeCap>500MB</totalSizeCap>  <!-- Allow 500MB total instead of 100MB -->
</rollingPolicy>
```

### Change Log Format

Customize the pattern in the encoder:

```xml
<encoder>
    <!-- Compact format -->
    <pattern>%d{HH:mm:ss} %-5level %logger{20} - %msg%n</pattern>

    <!-- Detailed format with thread and location -->
    <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [%file:%line] - %msg%n</pattern>

    <!-- Minimal format -->
    <pattern>%d{HH:mm:ss} %msg%n</pattern>
</encoder>
```

## Log Levels

- **ERROR**: Critical failures requiring immediate attention
- **WARN**: Issues that don't prevent operation but should be investigated
- **INFO**: Normal workflow events (default for acquisition log)
- **DEBUG**: Detailed information for troubleshooting
- **TRACE**: Very detailed information (not currently used)

## Using Project-Specific Logging

### Automatic Integration in Workflows

Workflows should enable project-specific logging at the start and disable it at the end. The recommended pattern uses try-with-resources for guaranteed cleanup:

```java
public class MyWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(MyWorkflow.class);

    public void execute(Project<?> project, String sampleName) {
        // Enable project logging - logs go to project folder
        try (ProjectLogger.Session session = ProjectLogger.start(project)) {

            if (!session.isActive()) {
                logger.warn("Could not enable project logging, using centralized log only");
            }

            logger.info("Starting workflow for sample: {}", sampleName);
            logger.info("Project: {}", project.getName());

            // All logger calls below will go to both:
            // 1. Centralized log: <QuPath>/logs/qpsc/qpsc-acquisition.log
            // 2. Project log: <Project>/acquisition.log

            performAcquisition();
            performStitching();

            logger.info("Workflow completed successfully");

        } // ProjectLogger automatically disabled here
        catch (Exception e) {
            logger.error("Workflow failed", e);
            throw e;
        }
    }
}
```

### Manual Control

If try-with-resources isn't suitable, use manual enable/disable:

```java
ProjectLogger.enable(project);
try {
    // Your workflow code
} finally {
    // ALWAYS disable in finally block
    ProjectLogger.disable();
}
```

### Checking Status

```java
if (ProjectLogger.isEnabled()) {
    String path = ProjectLogger.getCurrentProjectPath();
    logger.info("Logging to project: {}", path);
}
```

### Multiple Concurrent Workflows

The `ProjectLogger` is thread-safe and uses thread-local storage. Each thread can have its own active project log:

```java
// Thread 1
try (ProjectLogger.Session s1 = ProjectLogger.start(project1)) {
    logger.info("Working on project 1");
}

// Thread 2 (concurrent)
try (ProjectLogger.Session s2 = ProjectLogger.start(project2)) {
    logger.info("Working on project 2");
}
```

## Common Logging Scenarios

### Troubleshooting Connection Issues

1. Enable debug logging for socket client:
   ```xml
   <logger name="qupath.ext.qpsc.service.microscope.MicroscopeSocketClient" level="DEBUG" />
   ```

2. Enable full debug log (see above)

3. Check logs for connection errors and retry attempts

### Debugging Coordinate Transformations

Enable debug for transformation classes:
```xml
<logger name="qupath.ext.qpsc.utilities.TransformationFunctions" level="DEBUG" />
<logger name="qupath.ext.qpsc.utilities.TilingUtilities" level="DEBUG" />
```

### Analyzing Acquisition Failures

Check acquisition log for:
- Error messages with stack traces
- Warning messages before the failure
- Last successful operation before failure

### Monitoring Long-Running Acquisitions

The filtered acquisition log shows:
- Acquisition start confirmation
- Progress milestones (without repetitive updates)
- Completion or failure status
- Total acquisition time

## Filtering Details

### Currently Filtered Messages

The following patterns are excluded from the acquisition log:

1. **"Stage XY position: ("** - Repetitive position queries during health checks
2. **"Health check passed"** - Periodic connection health verifications
3. **"Health check failed"** - Failed health checks (still logged at WARN level)
4. **"Heartbeat received:"** - Test workflow heartbeat messages
5. **"Heartbeat client"** - Heartbeat client lifecycle messages
6. **"Heartbeat server"** - Heartbeat server lifecycle messages
7. **"PROGRESS UPDATE:"** - File acquisition progress updates (e.g., "PROGRESS UPDATE: 42 of 150 files")
8. **"X of Y files"** - Any message containing file count patterns
9. **"directory contents:"** - Verbose stitching directory listings
10. **"Initial tile base directory contents:"** - Directory state logging before stitching
11. **"Directory state after processing angle"** - Directory state logging during multi-angle stitching
12. **"Available directories in tile base"** - Directory availability checks
13. **"Birefringence directory contents:"** - Birefringence image processing details
14. **"Sum directory contents:"** - Sum image processing details
15. **"Found X .tif"** - .tif file counting messages
16. **"X OME-TIFF files"** - OME-TIFF file counting messages
17. **Low-level socket messages** - INFO/DEBUG messages from MicroscopeSocketClient containing "Stage" or "Health"

### Why These Are Filtered

#### Health Check Spam
During long acquisitions, the microscope socket client performs health checks every 30 seconds by querying the stage position. This generates messages like:

```
13:45:12.123 INFO  MicroscopeSocketClient - Stage XY position: (1234.5, 6789.0)
13:45:12.125 DEBUG MicroscopeSocketClient - Health check passed
```

For a 2-hour acquisition, this creates 240+ repetitive log entries that obscure important events.

#### File Progress Spam
During tile acquisition, progress is monitored by checking for completed files. This generates frequent updates:

```
14:23:15.456 INFO  UIFunctions - PROGRESS UPDATE: 1 of 150 files
14:23:17.789 INFO  UIFunctions - PROGRESS UPDATE: 2 of 150 files
14:23:20.123 INFO  UIFunctions - PROGRESS UPDATE: 3 of 150 files
```

For a 150-tile acquisition, this creates 150 log entries showing incremental progress.

#### Stitching Directory Spam
During multi-angle stitching, the system logs directory contents before/after each angle is processed:

```
15:30:12.456 INFO  StitchingHelper - Initial tile base directory contents:
15:30:12.457 INFO  StitchingHelper -   - -5.0
15:30:12.458 INFO  StitchingHelper -   - 0.0
15:30:12.459 INFO  StitchingHelper -   - 5.0
15:30:12.460 INFO  StitchingHelper -   - 90.0
15:30:25.123 INFO  StitchingHelper - Birefringence directory contents:
15:30:25.124 INFO  StitchingHelper -   - tile_001_001.tif
15:30:25.125 INFO  StitchingHelper -   - tile_001_002.tif
```

For multi-angle acquisitions, this can create hundreds of directory listing entries.

#### .tif File Counting Spam
The system tracks .tif files during stitching operations:

```
15:45:10.123 INFO  TileProcessingUtilities - Found 48 existing OME-TIFF files before stitching
15:46:25.456 INFO  TileProcessingUtilities - Found 4 new OME-TIFF files to rename and import
```

**The filtered acquisition log removes this noise while keeping all warnings, errors, and major workflow events.**

## Performance Impact

The logging system has minimal performance impact:

- Filtered messages are excluded at the appender level
- No string formatting occurs for filtered messages
- Rolling file policies operate asynchronously
- Disk I/O is buffered

## Rebuilding After Changes

After modifying `logback.xml`:

1. **No rebuild needed** - Logback reads the configuration file at runtime
2. Restart QuPath to apply changes
3. Changes take effect immediately on next extension load

## Troubleshooting Logging Issues

### Logs not being created

1. Check QuPath has write permissions to the working directory
2. Verify `logback.xml` is in the built extension JAR:
   ```bash
   jar tf build/libs/qupath-extension-qpsc-*.jar | grep logback
   ```
3. Check console for Logback initialization errors

### Too much/too little logging

- Adjust log levels in `logback.xml`
- Modify filter expressions to include/exclude patterns
- Check logger additivity settings

### Log files growing too large

- Reduce `maxHistory` to keep fewer days
- Lower `totalSizeCap` to limit total size
- Increase filtering to exclude more messages

## Additional Resources

- [Logback Documentation](https://logback.qos.ch/manual/)
- [SLF4J Documentation](https://www.slf4j.org/manual.html)
- [QuPath Logging Guide](https://qupath.readthedocs.io/)
