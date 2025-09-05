# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build System & Common Commands

This project uses Gradle with the QuPath extension convention plugin. Java 21+ is required.

**Build and Development:**
```bash
# Build the extension JAR
./gradlew build

# Build shadow JAR (includes all dependencies)
./gradlew shadowJar

# Run all tests
./gradlew test

# Run single test class
./gradlew test --tests "qupath.ext.qpsc.ConfigurationTestRunner"

# Run specific test method
./gradlew test --tests "qupath.ext.qpsc.QPProjectFunctionsTest.testCreateProject"

# Clean build artifacts
./gradlew clean

# Check for deprecation warnings and unchecked operations
./gradlew compileJava
```

**Testing Configuration:**
Tests require JavaFX modules and special JVM arguments. The test task is configured with:
- `--add-modules javafx.base,javafx.graphics,javafx.controls`  
- `--add-opens javafx.graphics/javafx.stage=ALL-UNNAMED`

## Architecture Overview

QPSC bridges QuPath's digital pathology environment with microscope control via Pycro-Manager. The extension enables annotation-driven targeted acquisition where users define regions in QuPath and automatically acquire high-resolution microscopy data.

### Core Workflow Pattern
```
QuPath Annotation → Coordinate Transform → Microscope Control → 
Image Acquisition → Stitching → Import Back to QuPath
```

### Package Architecture

**`controller/`** - Workflow orchestration
- `QPScopeController`: Main entry point routing menu selections to workflows
- `BoundingBoxWorkflow`: Complete acquisition workflow from user input to final stitched image
- `ExistingImageWorkflow`: Targeted acquisition on existing images with coordinate transformation  
- `MicroscopeAlignmentWorkflow`: Semi-automated alignment between QuPath and microscope coordinates
- `TestWorkflow`: Hardware connectivity testing

**`modality/`** - Pluggable imaging mode system
- `ModalityHandler`: Interface for modality-specific acquisition parameters and UI
- `ModalityRegistry`: Runtime registration and lookup of modality handlers
- `ppm/PPMModalityHandler`: Polarized light microscopy with multi-angle rotation sequences

**`service/`** - External system integration  
- `AcquisitionCommandBuilder`: Constructs Pycro-Manager CLI commands from acquisition parameters
- `microscope/MicroscopeSocketClient`: Socket-based communication with microscope control server

**`ui/`** - User interface components
- Dialog controllers for each workflow step (SampleSetup, BoundingBox, etc.)
- `UIFunctions`: Shared UI utilities for progress tracking and error handling
- Modality-specific UI components (e.g., `ppm/ui/PPMBoundingBoxUI`)

**`utilities/`** - Core functionality
- `MicroscopeConfigManager`: Singleton for YAML configuration management with LOCI resource lookup
- `QPProjectFunctions`: QuPath project creation and metadata management
- `TilingUtilities`: Tile grid computation and coordinate transformations
- `ImageMetadataManager`: Multi-sample project metadata tracking
- Coordinate transformation utilities and image processing helpers

### Configuration System

The system uses hierarchical YAML configuration:
- **Microscope-specific config**: Hardware settings, modalities, acquisition profiles
- **Shared LOCI resources**: Hardware component lookup tables across multiple microscopes
- **Runtime preferences**: User settings stored via QuPath's preference system

`MicroscopeConfigManager` provides type-safe access with automatic resource resolution for LOCI references.

### Modality Plugin System

Modalities are registered via `ModalityRegistry` and provide:
- Rotation angles and exposures for the modality
- Optional UI components for modality-specific parameters  
- Angle override logic for per-acquisition customization

New modalities implement `ModalityHandler` and register with a prefix (e.g., "ppm" matches "ppm_20x").

### Multi-Sample Project Support

The extension automatically tracks:
- **Image Collections**: Groups related images from the same physical slide
- **XY Offsets**: Physical positions for coordinate transformation
- **Flip Status**: Critical for coordinate system alignment
- **Parent Relationships**: Links between macro images and sub-acquisitions

### Key Integration Points

**QuPath Integration:**
- Extends via `QuPathExtension` interface in `SetupScope`
- Uses QuPath's project system for data organization
- Integrates with annotation system for region definition

**Microscope Communication:**
- Socket-based real-time communication with Pycro-Manager
- Heartbeat monitoring for long-running acquisitions
- Asynchronous progress reporting and error handling

**Coordinate Systems:**
- Handles transformations between QuPath pixel coordinates and physical microscope stage positions
- Supports image flipping and rotation for alignment
- Automatic coordinate validation against stage limits

## Development Notes

**Important Dependencies:**
- Requires `qupath-extension-tiles-to-pyramid:0.1.0` for stitched image creation
- Uses SnakeYAML for configuration parsing
- JavaFX for all UI components
- Mockito for testing microscope interactions

**Testing Strategy:**
- Unit tests for utilities and configuration management
- Mock microscope server for integration testing  
- JavaFX-based UI component testing

**Configuration Validation:**
- `QPScopeChecks.validateMicroscopeConfig()` checks required settings on startup
- Workflows are disabled if configuration is invalid
- Missing hardware components are handled gracefully

**Thread Safety:**
- Modality registry uses `ConcurrentHashMap`
- UI updates always use `Platform.runLater()`
- Background acquisition uses daemon thread pools