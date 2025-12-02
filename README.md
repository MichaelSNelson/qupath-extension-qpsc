# QuPath extension template

[Documentation for old version of qp-scope](https://docs.google.com/document/d/1XBRZRJ0p-M71GUEMJQ4xSMDFfq8fcTMy6KwfbtxXz-Q/edit?tab=t.0)
[Documentation for setup of qpsc](https://docs.google.com/document/d/1XBRZRJ0p-M71GUEMJQ4xSMDFfq8fcTMy6KwfbtxXz-Q/edit?tab=t.0)

[PPM project](https://docs.google.com/document/u/3/d/1XefVDE7qYCOOUUUYZDh4zW0qORQXOUilt47npvDIW3M/mobilebasic#heading=h.ywwydiewamwm)
[Software design MVC]()


# QuPath Scope Control (QPSC) Extension

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](#)
[![QuPath Version](https://img.shields.io/badge/qupath-0.5.1+-blue)](#)
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](#)

## Overview

**QP Scope Control (QPSC)** is a modular extension for [QuPath](https://qupath.github.io/) that enables **automated stage control, microscope integration, and workflow-driven image acquisition** from within the QuPath GUI.  
The extension bridges QuPath, Python-based microscope controllers (e.g., Pycro-Manager), and custom acquisition workflows—enabling seamless, reproducible, and high-throughput imaging directly from your digital pathology projects.

---

## Features

- **Automated Stage Control**: Move XY, Z, and rotational (polarizer) stages with GUI bounds checking.
- **Flexible Acquisition Workflows**:
    - **Bounding Box Tiling**: Define a region in QuPath, auto-compute acquisition tiles, and trigger microscopy scans.
    - **Existing Image Registration**: Register new tile scans to previously acquired macro images with affine transformation support.
    - **Background Collection**: Automated flat-field correction image acquisition with adaptive exposure control for consistent background intensities.
    - **Polarizer Calibration (PPM)**: Automated rotation stage calibration to find crossed polarizer positions via angle sweep and sine curve fitting.
    - **Autofocus Configuration Editor**: GUI editor for per-objective autofocus parameters (focus steps, search range, and spatial frequency).
- **Modality Handlers**: Imaging modes are resolved through pluggable handlers (e.g., PPM). Modalities with no special requirements use a no-op handler.
- **Integration with Python Controllers**: Robust socket-based communication with real-time progress reporting and heartbeat monitoring between QuPath and your Python microscope backend.
- **Project & Data Management**: Automatic project creation, tile config, and stitched OME-TIFF integration.
- **Extensible GUI**: Easily add dialogs for new acquisition/analysis routines.
- **Error Handling**: User notifications for bounds violations, acquisition errors, and resource validation.

> **Note:** Polarized (PPM) acquisitions always use the PPM modality prefix (e.g., `ppm_20x`). Even at 90° rotation the run is still PPM rather than "brightfield". Modalities without the `ppm` prefix perform single-pass acquisitions with no polarization.

---

## Getting Started

### Prerequisites

- [QuPath 0.6.0+](https://qupath.github.io/)
- Java 21+
- Python 3.8+ (with your microscope control scripts, e.g., Pycro-Manager)
- Basic understanding of your microscope’s YAML configuration and available CLI interface

### Installation

1. **Clone this repository:**
    ```bash
    git clone https://github.com/your-lab/qupath-extension-qpsc.git
    cd qupath-extension-qpsc
    ```
2. **Build and copy the extension JAR** to your QuPath `extensions/` folder. Or, more easily, drag and drop the jar file into an open QuPath window.
3. **Configure** your microscope YAML (see `config_PPM.yml` sample) and shared hardware resource file (`resources/resources_LOCI.yml`).
4. **Edit your Python controller path** in the QuPath preferences as needed.

This extension requires qupath-extension-tiles-to-pyramid to create the pyramidal ome.tif files that will be added to your project.

### Usage

- **Launch QuPath** and open the "QP Scope" menu.
- **Acquisition Workflows**:
  - **"Start with Bounding Box"**: Acquire a defined region with automatic tiling.
  - **"Start with Existing Image"**: Register new high-res scans to a pre-existing macro image using affine transforms and annotation selection.
  - **"Microscope to Microscope Alignment"**: Semi-automated alignment workflow for coordinate transformation between QuPath and microscope coordinates.
- **Calibration & Configuration**:
  - **"Collect Background Images"**: Acquire flat-field correction backgrounds with adaptive exposure control. The system automatically adjusts exposure times to reach target intensities for consistent background quality.
  - **"Polarizer Calibration (PPM)"**: Calibrate PPM rotation stage to determine crossed polarizer positions. Sweeps rotation angles, fits data to sine curve, and generates a report with suggested `config_PPM.yml` angles. Run this only after optical component changes.
  - **"Autofocus Settings Editor"**: Edit per-objective autofocus parameters in a user-friendly dialog. Configure focus steps (n_steps), Z search range (search_range_um), and spatial frequency (n_tiles) for each objective. Settings stored in `autofocus_{microscope}.yml` for easy management.
- **Utilities**:
  - **"Basic Stage Control"**: Manual stage movement interface for testing.
  - **"Server Connection Settings"**: Configure socket communication with Python microscope server.

---
Multi-Sample Project Support
QPSC supports managing multiple samples within a single QuPath project through an automatic metadata tracking system. This enables complex multi-slide studies while maintaining proper data organization and acquisition validation.
How Metadata Works
Each image in a project is automatically tagged with metadata to track:

Image Collection: Groups related images (e.g., all acquisitions from the same physical slide)
XY Offsets: Physical position on the slide in micrometers for precise re-acquisition
Flip Status: Whether the image has been flipped (critical for microscope alignment)
Sample Name: Identifies which physical sample the image represents
Parent Relationships: Links sub-images to their source images

Key Behaviors

Automatic Collection Assignment

First image in a project gets image_collection=1
New unrelated images increment the collection number
Sub-images inherit their parent's collection number


Flip Validation

When X/Y flips are enabled in preferences, only flipped images can be used for acquisition
Prevents acquisition errors due to coordinate system mismatches
Original (unflipped) images are preserved with all annotations


Position Tracking

Each image stores its offset from the slide corner
Sub-images calculate their position relative to the parent
Enables accurate stage positioning for multi-region acquisition



Multi-Sample Workflow Example

Import multiple slides into one project

Each gets a unique image_collection number
Metadata tracks which images belong together


Create flipped versions if needed

Use the "Create Flipped Duplicate" function
Annotations and hierarchy are automatically transformed
Both versions exist in the project with proper metadata


Acquire sub-regions from any image

Extension validates flip status before acquisition
Sub-images inherit the parent's collection
All related images stay grouped by metadata



Best Practices

Let the system manage metadata automatically - manual editing may break workflows
When working with flipped images, always use the flipped version for acquisition
Use descriptive sample names when setting up projects for easier identification
Sub-images from the same parent will share the same collection number for easy filtering

This metadata system operates transparently in the background, ensuring data integrity while supporting complex multi-sample workflows.

---

## Image Naming and Metadata System

QPSC uses a flexible, user-configurable image naming system that balances human readability with comprehensive metadata storage.

### Default Naming Pattern

Images are named using a clean, minimal format by default:
```
SampleName_001.ome.tif
SampleName_002.ome.zarr
```

For multi-angle acquisitions (e.g., PPM):
```
SampleName_001_7.0.ome.zarr
SampleName_001_-7.0.ome.zarr
SampleName_002_7.0.ome.zarr
```

**Key Points:**
- Index increments per acquisition/annotation, **NOT per angle**
- Angles distinguish images within the same acquisition
- All acquisition information is stored in QuPath metadata

### Customizable Filename Components

Users can configure which information appears in filenames via **QuPath Preferences → QPSC Extension**:

**Image name includes:**
- ☐ **Objective** - Add magnification (e.g., `SampleName_20x_001.ome.tif`)
- ☐ **Modality** - Add imaging mode (e.g., `SampleName_ppm_001.ome.tif`)
- ☐ **Annotation** - Add region name (e.g., `SampleName_Tissue_001.ome.tif`)
- ☑ **Angle** - Add polarization angle (defaults to **ON** for PPM - critical for distinguishing images!)

**Combining preferences:**
```
With Modality + Objective + Angle:
SampleName_ppm_20x_001_7.0.ome.zarr
```

### Complete Metadata Storage

**Important:** Regardless of filename configuration, **ALL** acquisition information is stored in QuPath metadata:
- Sample name
- Modality (ppm, bf, etc.)
- Objective/magnification
- Polarization angle (for multi-angle)
- Annotation name
- Image index
- Image collection number
- XY offsets (microns)
- Flip status
- Parent image relationships

Metadata can be viewed in QuPath's **Image → Image Properties** panel.

### Sample Name Validation

Sample names are validated for cross-platform filename safety:
- **Allowed**: Letters, numbers, spaces, underscores, hyphens
- **Blocked**: `/ \ : * ? " < > | newlines`
- **Protected**: Windows reserved names (CON, PRN, AUX, NUL, COM1-9, LPT1-9)
- **Automatic sanitization**: Invalid characters replaced with underscores

**Workflows:**
- **BoundingBox Workflow**: User specifies sample name (editable, validated)
- **ExistingImage Workflow**: Defaults to current image name without extension (editable)

### Multi-Sample Projects

Projects can now contain multiple samples with distinct names:
- Each sample can have its own name (no longer tied to project name)
- Metadata tracks which images belong to which sample
- Essential for collaborative studies with multiple specimens

---

## Calibration Workflows

### Background Collection

**Purpose**: Acquire flat-field correction images for improved image quality and quantitative analysis.

**Key Features**:
- **Adaptive Exposure Control**: Automatically adjusts exposure times to reach target grayscale intensities (e.g., 245 for PPM 90°, 125 for PPM 0°)
- **Fast Convergence**: Typically achieves target intensity in 2-5 iterations using proportional control algorithm
- **Accurate Metadata**: Records actual exposure times used (not requested values) for reproducibility
- **Modality Support**: Works with all imaging modes (PPM, brightfield, etc.)

**When to Use**:
- Initial microscope setup
- After light source changes or bulb replacement
- When image quality degrades
- For quantitative imaging requiring flat-field correction

**Workflow**:
1. Position microscope at clean, blank area (uniform background)
2. Select **"Collect Background Images"** from menu
3. Choose modality, objective, and output folder
4. Review/adjust angles and initial exposure estimates
5. System acquires backgrounds, adjusting exposure automatically
6. Metadata files saved with actual exposure values for each angle

### Polarizer Calibration (PPM)

**Purpose**: Determine exact hardware offset (`ppm_pizstage_offset`) for PPM rotation stage calibration.

**Key Features**:
- **Two-Stage Calibration**: Coarse sweep to locate minima, then fine sweep for exact hardware positions
- **Hardware Position Detection**: Works directly with encoder counts for precise calibration
- **Automatic Offset Calculation**: Determines the exact hardware position for optical angle reference (0°)
- **Sine Curve Fitting**: Uses `scipy` to fit intensity vs. angle data for validation
- **Comprehensive Report**: Generates text file with exact hardware positions and config recommendations

**When to Use**:
- **CRITICAL**: After installing or repositioning rotation stage hardware
- After reseating or replacing the rotation stage motor
- After optical component changes that affect polarizer alignment
- To recalibrate `ppm_pizstage_offset` in `config_PPM.yml`
- **NOT needed** for routine imaging sessions or between samples

**Two-Stage Calibration Process**:

**Stage 1 - Coarse Sweep:**
- Sweeps full 360° rotation in hardware encoder counts
- User-defined step size (default: 5°, equivalent to 5000 encoder counts for PI stage)
- Identifies approximate locations of 2 crossed polarizer minima (180° apart)
- Takes ~90 seconds at 5° steps

**Stage 2 - Fine Sweep:**
- Narrow sweep around each detected minimum
- Very small steps (0.1°, equivalent to 100 encoder counts)
- Determines exact hardware position of each intensity minimum
- Takes ~20 seconds per minimum (40 seconds total for 2 minima)

**Workflow**:
1. Position microscope at uniform, bright background
2. Select **"Polarizer Calibration (PPM)..."** from menu
3. Configure calibration parameters:
   - Coarse step size (default: 5°; recommended range: 2-10°)
   - Exposure time (default: 10ms; keep short to avoid saturation)
4. Start calibration (~2 minutes total for full two-stage calibration)
5. Review generated report containing:
   - **Exact hardware positions** (encoder counts) for each crossed polarizer minimum
   - **Recommended `ppm_pizstage_offset` value** (hardware position to use as 0° reference)
   - Optical angles relative to recommended offset
   - Intensity statistics and validation data
   - Raw data from both coarse and fine sweeps
6. **Update `config_PPM.yml`** with recommended offset value

**Output Report Includes**:
```
EXACT HARDWARE POSITIONS (CROSSED POLARIZERS)
================================================================================
Found 2 crossed polarizer positions:

  Minimum 1:
    Hardware Position: 50228.7 encoder counts
    Optical Angle: 0.00 deg (relative to recommended offset)
    Intensity: 118.3

  Minimum 2:
    Hardware Position: 230228.7 encoder counts
    Optical Angle: 180.00 deg (relative to recommended offset)
    Intensity: 121.5

Separation between minima: 180000.0 counts (180.0 deg)
Expected: 180000.0 counts (180.0 deg)

CONFIG_PPM.YML UPDATE RECOMMENDATIONS
================================================================================
CRITICAL: Update ppm_pizstage_offset to the recommended value below.
This sets the hardware reference position for optical angle 0 deg.

ppm_pizstage_offset: 50228.7

After updating the offset, you can use the following optical angles:

rotation_angles:
  - name: 'crossed'
    tick: 0   # Reference position (hardware: 50228.7)
    # OR tick: 180   # Alternate crossed (hardware: 230228.7)
  - name: 'uncrossed'
    tick: 90  # 90 deg from crossed (perpendicular)
```

**Important Notes**:
- This calibration determines the **hardware offset itself**, not just optical angles
- The offset value is specific to your rotation stage and optical configuration
- After updating `ppm_pizstage_offset`, the optical angle convention (tick values) remains simple: 0°, 90°, 180°
- Hardware automatically converts: `hardware_position = (tick * 1000) + ppm_pizstage_offset`

### Autofocus Settings Editor

**Purpose**: Configure per-objective autofocus parameters in an easy-to-use GUI without manually editing YAML files.

**Key Features**:
- **Per-Objective Configuration**: Set different autofocus parameters for each objective (10X, 20X, 40X, etc.)
- **Three Key Parameters**:
  - **n_steps**: Number of Z positions to sample during autofocus (higher = more accurate but slower)
  - **search_range_um**: Total Z range to search in micrometers (centered on current position)
  - **n_tiles**: Spatial frequency - autofocus runs every N tiles during large acquisitions (lower = more frequent but slower)
- **Live Validation**: Warns about extreme values that may cause poor performance
- **Separate Storage**: Settings stored in `autofocus_{microscope}.yml` (e.g., `autofocus_PPM.yml`)
- **Working Copy**: Edit multiple objectives before saving to file

**When to Use**:
- Initial microscope setup
- After changing objectives or optical configuration
- When autofocus performance needs tuning (too slow, not accurate enough, etc.)
- To optimize autofocus frequency for different sample types

**Workflow**:
1. Select **"Autofocus Settings Editor..."** from menu
2. Select objective from dropdown
3. Edit parameters:
   - **n_steps**: Typical range 5-20 (default: 9-15 depending on objective)
   - **search_range_um**: Typical range 10-50 μm (default: 10-15 μm)
   - **n_tiles**: Typical range 3-10 (default: 5-7)
4. Switch between objectives to edit other settings (changes saved automatically)
5. Click **"Write to File"** to save all settings
6. Click **"OK"** to save and close, or **"Cancel"** to discard unsaved changes

**Parameter Guidance**:
- **Higher magnification** → More n_steps, smaller search_range_um (e.g., 40X: 15 steps, 10 μm range)
- **Lower magnification** → Fewer n_steps, larger search_range_um (e.g., 10X: 9 steps, 15 μm range)
- **Thick samples** → Increase search_range_um
- **Time-critical acquisitions** → Increase n_tiles (less frequent autofocus), decrease n_steps
- **Critical focus quality** → Decrease n_tiles (more frequent autofocus), increase n_steps

**Configuration File Format** (`autofocus_PPM.yml`):
```yaml
autofocus_settings:
  - objective: 'LOCI_OBJECTIVE_OLYMPUS_10X_001'
    n_steps: 9
    search_range_um: 15.0
    n_tiles: 5

  - objective: 'LOCI_OBJECTIVE_OLYMPUS_20X_POL_001'
    n_steps: 11
    search_range_um: 15.0
    n_tiles: 5

  - objective: 'LOCI_OBJECTIVE_OLYMPUS_40X_POL_001'
    n_steps: 15
    search_range_um: 10.0
    n_tiles: 7
```

**First-Time Use**:
- If `autofocus_{microscope}.yml` doesn't exist, editor loads sensible defaults
- Objectives from main config automatically populate
- Save creates the file with all configured objectives

---

📁 File Structure

```text

qupath-extension-qpsc/
├── .github/
├── .gradle/
├── .idea/
├── build/
├── gradle/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── qupath/
│   │   │       └── ext/
│   │   │           └── qpsc/
│   │   │               ├── controller/
│   │   │               ├── modality/
│   │   │               ├── preferences/
│   │   │               ├── service/
│   │   │               ├── ui/
│   │   │               ├── utilities/
│   │   │               ├── QPScopeChecks.java
│   │   │               └── SetupScope.java
│   │   └── resources/
│   │       └── qupath/
│   │           └── ext/
│   │               └── qpsc/
│   │                   └── ui/
│   │                       ├── interface.fxml
│   │                       └── strings.properties
│   └── test/
│       └── java/
│           └── qupath/
│               └── ext/
│                   └── qpsc/
│                       ├── CoordinateTransformationTest.java
│                       ├── QPProjectFunctionsTest.java
│                       └── WorkflowTests.java
├── resources/
│   ├── config_PPM.yml
│   ├── resources_LOCI.yml
│   └── ...
├── heartbeat_client.py
├── build.gradle.kts
├── settings.gradle.kts
├── .gitignore
├── README.md
└── project-structure.txt

```

Legend
controller/ – Main workflow logic for acquisition, bounding box, existing image, etc.

modality/ – Pluggable modality handlers (e.g., PPM) and rotation utilities.

preferences/ – User settings and persistent configuration.

service/ – Abstractions for CLI/Python process integration.

ui/ – User dialogs (JavaFX), UI controllers for user input and feedback.

utilities/ – Helpers for file IO, YAML/JSON, tiling, stitching, etc.

resources/ – Configuration files (YAML), FXML, localizable strings.

test/ – Unit and integration tests.

heartbeat_client.py – Python script for test/integration workflows.



Workflow Overview:
The diagram below illustrates the sequence of operations when a user performs an “Acquire by Bounding Box” workflow in the QP Scope extension. User input and configuration guide the Java workflow, which orchestrates microscope control via Python scripts, handles asynchronous stitching, and integrates the final OME-TIFF into the QuPath project.
### Bounding Box Acquisition Workflow
```mermaid
sequenceDiagram
    participant User
    participant Q as QuPath GUI
    participant Ext as QP Scope Extension
    participant WF as BoundingBoxWorkflow
    participant Py as Python CLI (PycroManager)
    participant Stitch as Stitcher
    participant Proj as QuPath Project

    Q->>Ext: Calls SetupScope.installExtension()
    Ext->>Q: Adds menu item
    User->>Ext: Bounding Box menu item selected
    Ext->>WF: QPScopeController.startWorkflow("boundingBox")

    WF->>User: Show sample setup dialog
    User->>WF: Enter sample/project info
    WF->>User: Show bounding box dialog
    User->>WF: Enter bounding box

    WF->>WF: Read prefs/config\n(Get FOV, overlap, etc)
    WF->>Proj: Create/open QuPath project

    WF->>WF: Write TileConfiguration.txt
    WF->>Py: Launch acquisition CLI (Python)
    Py-->>WF: Output progress (stdout)
    WF->>Q: Update progress bar

    WF->>Stitch: Start stitching (async)
    Stitch->>Proj: Add OME.TIFF to project

    WF->>Q: Show notifications/errors
```

## YAML Configuration
Microscope config: Describes imaging modes, detectors, objectives, stage limits, etc. (config_PPM.yml)

Shared resources: Centralized lookup for hardware IDs (cameras, objectives, stages), e.g., for multi-microscope setups (resources/resources_LOCI.yml).

## Development & Testing
Workflows are in controller/. GUI dialogs are in ui/.

Unit tests use JUnit and Mockito. See src/test/ for examples.

Extending: Add new dialogs, Python commands, or custom modalities by implementing a `ModalityHandler` and registering it via `ModalityRegistry.registerHandler`.

## Troubleshooting
No hardware connection? Check CLI path and microscope YAML.

Timeouts during acquisition? Adjust inactivity timeouts and check the Python script’s heartbeat.

Resource warnings? Verify the path to resources_LOCI.yml is correct and matches your microscope folder layout.

## License
MIT License (see LICENSE)

## Citation
If you use this extension in published work, please cite the QuPath platform and this repository.

Acknowledgments
Developed by LOCI, UW-Madison
With thanks to the QuPath community and everyone contributing to open-source microscopy.

For support, issues, and feature requests, please use GitHub Issues.