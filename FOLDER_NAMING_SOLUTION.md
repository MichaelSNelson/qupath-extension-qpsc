# Enhanced Folder Naming Solution

## Problem
The QuPath project folders were being created as `ppm_1` instead of the more informative `ppm_20x_1` format, which didn't provide enough information about the objective magnification used.

## Solution Overview

### 1. Created ObjectiveUtils Utility Class
**File**: `src/main/java/qupath/ext/qpsc/utilities/ObjectiveUtils.java`

- **`extractMagnification(String objectiveIdentifier)`**: Extracts magnification (e.g., "20x") from LOCI objective identifiers like "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001"
- **`createEnhancedFolderName(String baseScanType, String objectiveIdentifier)`**: Converts "ppm_1" + objective → "ppm_20x_1"

### 2. Enhanced AcquisitionCommandBuilder
**File**: `src/main/java/qupath/ext/qpsc/service/AcquisitionCommandBuilder.java`

- Added **`getEnhancedScanType()`** method that combines base scan type with extracted magnification
- Modified **`buildSocketMessage()`** to use enhanced scan type when sending to Python server
- Added import for `ObjectiveUtils`

### 3. Python Side Compatibility
**File**: `smart-wsi-scanner/src/smart_wsi_scanner/qp_acquisition.py`

The Python code was already designed to handle the new format:
- **Line 308**: `output_path = project_path / params["scan_type"] / params["region_name"]` uses the enhanced scan type
- **`BackgroundCorrectionUtils.get_modality_from_scan_type()`** expects and properly parses "PPM_20x_1" format

### 4. Added Tests
**File**: `src/test/java/qupath/ext/qpsc/utilities/ObjectiveUtilsTest.java`

Comprehensive test coverage for:
- Valid LOCI objective identifiers
- Different objective formats
- Invalid inputs and edge cases
- Real-world examples

## Results

| Before | After |
|--------|-------|
| `ppm_1` | `ppm_20x_1` |
| `ppm_2` | `ppm_10x_2` |
| `brightfield_1` | `brightfield_40x_1` |

## Benefits

1. **Clear Magnification Info**: Folders now show the objective magnification used
2. **Better Organization**: Multiple objectives can be easily distinguished
3. **Backwards Compatible**: Falls back to original format if magnification can't be extracted
4. **Python Ready**: Python server correctly handles the new path format
5. **Automated**: No manual intervention required - magnification is automatically extracted from configuration

## Implementation Details

- **Regex Pattern**: `(\\d+)[Xx](?:_|$|\\s)` matches various magnification formats
- **Enhanced Naming**: Inserts magnification before the count: `modality_count` → `modality_magnification_count`  
- **Fallback Logic**: If magnification can't be extracted, uses original scan type
- **Logging**: Comprehensive logging for debugging and verification

## Configuration Support

Works with all LOCI objective formats in the configurations:
- `LOCI_OBJECTIVE_OLYMPUS_20X_POL_001` → "20x"
- `LOCI_OBJECTIVE_OLYMPUS_10X_001` → "10x"  
- `LOCI_OBJECTIVE_NIKON_4X_002` → "4x"
- `LOCI_OBJECTIVE_OLYMPUS_40X_POL_001` → "40x"