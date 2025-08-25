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
- **Modality Handlers**: Imaging modes are resolved through pluggable handlers (e.g., PPM). Modalities with no special requirements use a no-op handler.
- **Integration with Python Controllers**: Robust CLI command execution, heartbeat monitoring, and real-time progress reporting between QuPath and your Python microscope backend.
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

- **Launch QuPath** and open the “QP Scope” menu.
- Use “Test Workflow” for stage movement and heartbeat demo.
- Use “Bounding Box Workflow” to acquire a defined region with automatic tiling.
- Use “Existing Image Workflow” to register new high-res scans to a pre-existing macro image, using affine transforms and annotation selection.

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