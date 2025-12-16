# QPSC Troubleshooting Guide & FAQ

**Last Updated:** December 16, 2025
**Target Audience:** Pathologists, researchers, and microscopy users
**Skill Level:** All levels (clear explanations provided)

---

## Table of Contents

1. [Quick Diagnostic Checklist](#quick-diagnostic-checklist)
2. [Getting Started Troubleshooting](#getting-started-troubleshooting)
3. [FAQ by Category](#faq-by-category)
4. [Error Messages Reference](#error-messages-reference)
5. [Component-by-Component Troubleshooting](#component-by-component-troubleshooting)
6. [Configuration Validation](#configuration-validation)
7. [Log Files and Diagnostics](#log-files-and-diagnostics)

---

## Quick Diagnostic Checklist

**Before starting any acquisition, verify ALL of these are working:**

- [ ] **Micro-Manager is running** and shows live camera view
- [ ] **Python server is running** (command window should be open with "Server started" message)
- [ ] **QuPath is open** with QPSC extension loaded (check Extensions menu)
- [ ] **Microscope hardware is initialized** in Micro-Manager (stage, camera, rotation stage if using PPM)
- [ ] **Configuration files exist** in the correct location (config_ppm.yml, resources_LOCI.yml)
- [ ] **Project folder exists** or you have permission to create one

**Quick Test:** Use Extensions > QPSC > Utilities > Server Connection Settings to verify communication between all components.

---

## Getting Started Troubleshooting

### Where to Look First

**1. Check the error dialog** - QuPath will show clear error messages with recommended actions

**2. Check QuPath logs** - Most helpful information:
   - Location: `<QuPath-Directory>/logs/qpsc/qpsc-acquisition.log`
   - Look for ERROR and WARN messages near the time of the problem

**3. Check Python server console** - If you see errors there, the server crashed or has issues

**4. Check Micro-Manager** - If Micro-Manager is frozen or showing errors, restart it first

### Most Common Issues (90% of problems)

1. **Python server not running** - Start it before using QPSC
2. **Micro-Manager not started** - Launch Micro-Manager first
3. **Hardware not initialized** - Load hardware configuration in Micro-Manager
4. **Wrong configuration file** - Check file paths in microscope config
5. **Missing pixel calibration** - Image metadata lacks pixel size information

---

## FAQ by Category

### Installation & Setup

#### Q: Where do I install the QPSC extension?

**A:** Copy `qupath-extension-qpsc-0.2.0-all.jar` to your QuPath extensions folder:
- **Windows:** `C:\Users\<YourName>\QuPath\extensions\`
- **Mac:** `~/Library/Application Support/QuPath/extensions/`
- **Linux:** `~/.qupath/extensions/`

Then restart QuPath.

#### Q: How do I know if the extension loaded correctly?

**A:** Check the Extensions menu - you should see "QPSC" with options like "Bounded Acquisition", "Acquire from Existing Image", etc. If not:
1. Check the extensions folder contains the .jar file
2. Look at QuPath console for error messages on startup
3. Verify you're using QuPath 0.6.0-rc4 or newer

#### Q: The extension won't load - says "dependency error"

**A:** You need both extensions:
1. `qupath-extension-tiles-to-pyramid-0.1.0-all.jar` (install this FIRST)
2. `qupath-extension-qpsc-0.2.0-all.jar` (install after)

Both must be in the extensions folder. The tiles-to-pyramid extension handles image stitching.

### Configuration Files

#### Q: Where are the configuration files located?

**A:** Default location depends on your installation. Common locations:
- Windows: `C:\Users\<YourName>\QPSC\configurations\`
- Linux/Mac: `~/QPSC/configurations/`

Files needed:
- `config_ppm.yml` - Main microscope configuration
- `resources_LOCI.yml` - Hardware component definitions
- `autofocus_PPM.yml` - Autofocus settings (optional, can be in YAML or config)

#### Q: How do I know which config file QuPath is using?

**A:** Check the QuPath preferences:
1. Edit > Preferences > QPSC
2. Look at "Microscope configuration file" field
3. This shows the currently loaded config file path

If the field is empty or shows a missing file, use "Browse" to select your config file.

#### Q: I changed the config file but nothing happened

**A:** Configuration files are loaded once at startup. To apply changes:
1. Save your changes to the YAML file
2. Restart QuPath completely
3. Verify changes in Edit > Preferences > QPSC

Some settings (like exposure times for backgrounds) are saved separately and need to be updated via the background collection workflow.

### Python Server

#### Q: How do I start the Python server?

**A:** Open a command window and run:
```bash
cd /path/to/smart-wsi-scanner
python -m smart_wsi_scanner.qp_server
```

You should see:
```
Server started on localhost:5000
Waiting for connections...
```

Keep this window open while using QPSC.

#### Q: Server won't start - says "Address already in use"

**A:** Another program is using port 5000. Two options:

**Option 1 (Recommended):** Stop the other server
```bash
# Find what's using the port (Windows)
netstat -ano | findstr :5000

# Kill the process (use PID from above)
taskkill /PID <process_id> /F
```

**Option 2:** Use a different port
- Edit `qp_server.py` and change the port number
- Update your microscope config file to match

#### Q: Server starts but QuPath can't connect

**A:** Check these:
1. **Firewall:** Windows Firewall might be blocking Python - allow it
2. **Port number:** Verify config file has same port as server (default: 5000)
3. **Server running:** Make sure server console is still open and not showing errors
4. **Test connection:** Use Extensions > QPSC > Utilities > Server Connection Settings

#### Q: Server crashes during acquisition with "UnicodeEncodeError"

**A:** This is a Windows encoding issue. The fix is already in the code, but if you see it:
- Your Python code has non-ASCII characters (like arrows, degrees symbols)
- Update to the latest version of smart-wsi-scanner
- If editing code yourself, use only ASCII characters in logging statements

### Micro-Manager Connection

#### Q: Error says "Hardware not initialized" or "MicroManager not running"

**A:** Start Micro-Manager first:
1. Launch Micro-Manager application
2. Load your hardware configuration (.cfg file)
3. Verify you can see live camera feed
4. Move the stage manually to confirm it responds
5. Now start QuPath and QPSC will connect

#### Q: Micro-Manager crashes when QPSC tries to acquire

**A:** This usually means:
1. **Stage limits exceeded:** Check if your acquisition region is within stage travel range
2. **Hardware conflict:** Close any other software controlling the microscope
3. **Micro-Manager needs restart:** It sometimes gets stuck - restart helps
4. **Not enough memory:** Check if Micro-Manager is set to circular buffer mode with reasonable size

#### Q: Stage doesn't move even though everything else works

**A:** Check in this order:
1. Can you move stage manually in Micro-Manager? If NO - hardware problem
2. Are stage limits set correctly in config? If NO - see configuration validation section
3. Is stage control enabled in Micro-Manager device properties? If NO - enable it
4. Look at Python server console for error messages about stage movement

### Coordinate System & Alignment

#### Q: My acquired images don't line up with the QuPath annotations

**A:** This is a coordinate alignment issue. Check:

1. **Image flip settings:** In the sample setup dialog, make sure "Invert X" / "Invert Y" match your microscope orientation
2. **Pixel size calibration:** Verify your image has correct pixel size metadata (check image properties in QuPath)
3. **Stage coordinate system:** Some microscopes have inverted stage coordinates - adjust flip settings to compensate

**To fix:**
- Use Extensions > QPSC > Microscope Alignment to calibrate coordinate transformation
- Mark reference points in QuPath and on microscope stage
- System will calculate the correct transformation automatically

#### Q: What does "Invert X" and "Invert Y" mean?

**A:** These flip the coordinate system between QuPath and the microscope stage:

- **Invert X:** Flips left/right (QuPath left = stage right)
- **Invert Y:** Flips up/down (QuPath up = stage down)

Most microscopes need **Invert Y = ON** because stage coordinates increase downward while QuPath coordinates increase upward.

**How to find the right settings:**
1. Mark a point in QuPath (note its X,Y position)
2. Start a tiny test acquisition at that point
3. If the acquired region is in the wrong place, try different flip settings
4. Save the working settings - they'll be remembered

#### Q: Coordinate transformation fails - "No valid transformation found"

**A:** The alignment calibration needs more data points:
1. You need at least 3 reference points for reliable transformation
2. Points should be spread across the slide (not clustered)
3. Points must be on the same image (can't mix images)
4. Reference points must have both QuPath and stage coordinates recorded

### Acquisition Problems

#### Q: Acquisition starts but no images appear

**A:** Check Python server console for errors. Common causes:

1. **Camera not triggering:** Check Micro-Manager can acquire images manually
2. **Wrong save location:** Python server might be saving to unexpected folder - check console messages
3. **Permissions error:** Python server can't write to the output directory
4. **Out of disk space:** Check available space on target drive

#### Q: First few tiles are black or very dark

**A:** Camera needs time to stabilize after starting acquisition:
1. Increase "settling time" in microscope config (default: 100ms, try 500ms)
2. Check camera exposure time is reasonable for your sample
3. Verify illumination is on and stable

#### Q: Images are overexposed (too bright/white)

**A:** Exposure time too long:
1. Reduce exposure time in angle selection dialog
2. For background collection, use lower exposures
3. Check if adaptive exposure is enabled (it should auto-adjust, but initial value might be wrong)

#### Q: Images are underexposed (too dark)

**A:** Exposure time too short or illumination problem:
1. Increase exposure time in angle selection dialog
2. Check microscope illumination is on at correct intensity
3. Verify camera gain settings in Micro-Manager

#### Q: Autofocus isn't working - images out of focus

**A:** Several possibilities:

**Tissue Detection Issue:**
- Autofocus might think there's no tissue at that position
- Check logs for "Tissue stats: texture=X" messages
- If texture values are low (< 0.005), tissue detection thresholds might need adjustment
- Edit `autofocus_PPM.yml` and lower `texture_threshold` value

**Autofocus Hardware:**
- Verify Z-stage works in Micro-Manager
- Check autofocus search range is reasonable (default 30um)
- Try manual focus workflow to verify Z-control works

**Configuration:**
- Check `n_steps` (number of focus steps) - should be 15-25 for most applications
- Verify `search_range_um` covers your sample thickness variation

#### Q: Autofocus is too slow

**A:** Reduce the number of autofocus positions:
1. Edit `autofocus_PPM.yml`
2. Increase `n_tiles` value (e.g., from 2 to 5)
3. `n_tiles=5` means autofocus every 5 tiles instead of every 2 tiles
4. Faster acquisition but less frequent focus updates

### Stitching Issues

#### Q: Stitching takes forever - QuPath freezes

**A:** This is expected behavior - stitching can take several minutes for large images:

1. **Don't click anything in QuPath** during stitching - this can cause crashes
2. A blocking dialog appears to prevent accidental interference
3. You can dismiss the dialog "at your own risk" but this may crash QuPath
4. Just wait - stitching time depends on number of tiles (2-10 minutes is normal)

**Stitching time guide:**
- 10 tiles: ~30 seconds
- 50 tiles: ~2 minutes
- 150 tiles: ~5-8 minutes
- 500+ tiles: ~10-20 minutes

#### Q: Stitched image has visible tile boundaries

**A:** This is normal for zero-overlap acquisitions:
1. Add overlap percentage in acquisition settings (try 10%)
2. Overlap helps stitching algorithm blend tiles smoothly
3. More overlap = better stitching but longer acquisition time

#### Q: Stitching fails - "TileConfiguration.txt not found"

**A:** The tile coordinate file is missing:
1. Check the temp tiles folder still exists (QuPath might have cleaned it up early)
2. Look for Python server errors during acquisition (acquisition might have failed silently)
3. Verify Python server completed acquisition successfully before stitching started

#### Q: Stitched image is in wrong location in QuPath

**A:** Pixel size calibration issue:
1. Check if the original image has pixel size metadata
2. Verify microscope config has correct `pixel_size_microns` value
3. For annotation-based acquisitions, pixel size MUST be set - check logs for warnings

### PPM-Specific Issues

#### Q: PPM angle selection dialog freezes when clicking OK

**A:** This was a bug that's been fixed. Make sure you have the latest version. If still happening:
1. Update to latest qupath-extension-qpsc
2. Check QuPath logs for "JavaFX Application Thread" errors
3. This shouldn't happen anymore, but if it does, force-quit QuPath and report the bug

#### Q: Warning says "exposure settings don't match background"

**A:** This is informational, not an error:
- You selected different exposure times than what's in your saved background images
- You can proceed anyway (background correction won't be applied)
- Or collect new background images with matching exposures

To fix permanently:
1. Run Extensions > QPSC > Utilities > Background Collection
2. Use the same angles and exposures you plan to use for acquisitions
3. System will use these backgrounds automatically in future acquisitions

#### Q: Some tiles in stitched image are very bright (saturated)

**A:** This was a rotation stage bug that's been fixed, but if you see it:

**Cause:** Wrong rotation angle during acquisition (likely 90 deg instead of intended angle)

**Fix:**
1. Update smart-wsi-scanner to latest version (includes rotation fixes)
2. Check Python server console during acquisition for "Angle sequence issue" warnings
3. If you see these warnings, the rotation stage is having trouble - may need hardware reset

**Workaround:** Restart Micro-Manager and Python server between acquisitions

#### Q: Birefringence images show tile-to-tile brightness variations

**A:** This was a normalization bug that's been fixed. Make sure you have latest version:
- Update smart-wsi-scanner to latest version
- Old version normalized each tile separately causing brightness mismatch
- New version uses consistent scaling across entire mosaic

### Background Collection

#### Q: What are background images and do I need them?

**A:** Background images correct for uneven illumination:

**What they do:** Divide your sample images by background images to remove illumination variations

**When to collect:**
- When you change objectives
- When you change modality (e.g., PPM to brightfield)
- When illumination settings change
- Periodically (recommended monthly or after lamp changes)

**How to collect:**
1. Remove your slide (focus on empty stage or coverslip)
2. Extensions > QPSC > Utilities > Background Collection
3. Select modality and objective
4. Set exposure times (use same as your sample acquisitions)
5. Acquire - takes 30-60 seconds

#### Q: Background collection fails with timeout

**A:** The Python server took too long to respond:
1. Check Python server console for errors
2. Verify camera is working (test in Micro-Manager)
3. Check rotation stage isn't stuck (for PPM backgrounds)
4. Increase timeout in config if you have a slow camera

#### Q: Should I use the same exposure for backgrounds and samples?

**A:** **YES - this is critical:**
- Background and sample exposures MUST match for proper correction
- If they don't match, background correction is automatically disabled
- System will warn you if exposures don't match
- Use the same exposure times you plan to use for your samples

### File Management

#### Q: Where are my acquired images saved?

**A:** Location depends on workflow:

**Bounded Acquisition:**
- Creates new QuPath project in: `<Projects Folder>/<Sample Name>/`
- Stitched images in: `<Sample Name>/SlideImages/`
- Temporary tiles in: `<Sample Name>/TempTiles/` (auto-deleted after stitching)

**Acquire from Existing Image:**
- Uses currently open project
- Stitched images added to existing project
- Check project folder in QuPath (Project > Show project location)

#### Q: Can I delete the TempTiles folder?

**A:** **After stitching completes - YES**
- QuPath automatically deletes temp tiles after successful stitching
- If stitching failed, tiles remain for debugging
- You can manually delete TempTiles folder after confirming stitched images are correct
- Keep tiles if you need to re-run stitching with different parameters

#### Q: Stitched images are huge - can I reduce file size?

**A:** Yes, several options:

**Option 1: Use OME-ZARR format (recommended)**
1. Edit > Preferences > QPSC
2. Change "Stitching output format" to OME_ZARR
3. 20-30% smaller files, 2-3x faster to create

**Option 2: Reduce overlap**
- Less overlap = fewer total tiles = smaller file
- But too little overlap hurts stitching quality

**Option 3: Increase compression**
- Edit tiles-to-pyramid config if you know how
- Default LZW compression is already pretty good

#### Q: ZARR files won't open in QuPath

**A:** ZARR is a directory format:
- The `.ome.zarr` folder is the complete image
- Don't move or delete files inside the folder
- QuPath should recognize ZARR automatically
- If not, check QuPath has bio-formats extension installed
- Fallback: Switch to OME-TIFF format in preferences

### Workflow-Specific

#### Q: What's the difference between "Bounded Acquisition" and "Acquire from Existing Image"?

**A:**

**Bounded Acquisition:**
- For new acquisitions starting from scratch
- You specify physical coordinates or region size
- Creates a new QuPath project
- Use when you don't have an existing overview image

**Acquire from Existing Image:**
- For targeted acquisition on loaded images
- You draw annotations in QuPath on an existing image
- Adds acquisitions to current project
- Use when you have a macro/overview image and want to acquire specific regions

**Simple rule:** If you have an overview image already, use Acquire from Existing Image. If starting fresh, use Bounded Acquisition.

#### Q: "Acquire from Existing Image" says "No macro image available"

**A:** This workflow needs an overview/macro image to define coordinates:

**Fix:**
1. Acquire or load a macro image of your entire sample first
2. Open it in QuPath before starting the workflow
3. The macro image provides the coordinate reference system

**Alternative:** Use Bounded Acquisition instead, which doesn't need a macro image

#### Q: Can I run multiple acquisitions in one session?

**A:** Yes, with some notes:

**Bounded Acquisition:**
- Creates new project each time by default
- Or if project already open, adds to existing project
- Each sample gets its own folder structure

**Acquire from Existing Image:**
- Always adds to currently open project
- Can acquire multiple annotations in one workflow run
- Best for batch processing regions of interest

**Best practice:** Keep same project open to add multiple samples, or close/reopen between completely different experiments

### Performance & Optimization

#### Q: Acquisition is very slow - how can I speed it up?

**A:** Several factors affect speed:

**1. Autofocus frequency:**
- Edit `autofocus_PPM.yml`
- Increase `n_tiles` from 2 to 5 or even 10
- Autofocus less often = faster acquisition
- But risk of defocusing if sample isn't flat

**2. Overlap percentage:**
- Reduce overlap from 10% to 5% or even 0%
- Less overlap = fewer total tiles
- But stitching quality may suffer

**3. Camera exposure:**
- Shorter exposures = faster acquisition
- But images might be too dark
- Use adaptive exposure to find optimal balance

**4. Camera cooling/settling:**
- Reduce settling time in config (try 100ms instead of 500ms)
- Only if your camera stabilizes quickly

**5. Stitching format:**
- Use OME-ZARR instead of OME-TIFF
- 2-3x faster to create stitched images

#### Q: QuPath is using too much memory

**A:**
- Close unused projects
- Don't open multiple large stitched images simultaneously
- Increase QuPath memory limit (Edit > Preferences > Memory)
- Consider using lower resolution overview images

---

## Error Messages Reference

### Socket/Connection Errors

#### "Connection refused"

**Meaning:** QuPath can't reach the Python server

**Likely causes:**
- Python server not running
- Wrong port number in config
- Firewall blocking connection

**Solution:**
1. Start Python server
2. Verify port 5000 in both server and config
3. Check Windows Firewall settings

#### "Read timed out"

**Meaning:** Python server didn't respond in time

**Likely causes:**
- Micro-Manager crashed or frozen
- Python server busy with long operation
- Network issue (rare for localhost)

**Solution:**
1. Check if Micro-Manager is responsive
2. Check Python server console for errors
3. Restart both if necessary

#### "Hardware error: XY stage not initialized"

**Meaning:** Micro-Manager doesn't have stage control

**Solution:**
1. Launch Micro-Manager
2. Load hardware configuration
3. Verify stage shows in device list
4. Test stage movement manually

### Configuration Errors

#### "Pixel size must be set for annotation-based workflows"

**Meaning:** The image lacks pixel calibration data

**Likely causes:**
- Image imported without metadata
- Incorrect import settings
- Microscope config missing pixel_size_microns

**Solution:**
1. Check image properties in QuPath (right-click image > Image > Show metadata)
2. If pixel size is missing, add it manually (Image > Set pixel size)
3. Or ensure microscope config has correct pixel_size_microns value

#### "Configuration file not found"

**Meaning:** QuPath can't find the YAML config

**Solution:**
1. Edit > Preferences > QPSC
2. Use Browse button to select config_ppm.yml
3. Verify file exists at the specified path

#### "Missing required configuration key: exposures"

**Meaning:** YAML file structure is incorrect

**Solution:**
1. Check YAML file formatting (indentation matters!)
2. Compare with example config files
3. Look for missing or misspelled keys in the modalities section

### Acquisition Errors

#### "Stage position exceeds limits"

**Meaning:** Requested position is outside stage travel range

**Solution:**
1. Check stage_limits in microscope config
2. Reduce acquisition region size
3. Verify you're not trying to acquire off the slide

#### "Autofocus failed at position"

**Meaning:** Couldn't find focus at one or more positions

**Likely causes:**
- No tissue at that position (normal - acquisition continues)
- Z-stage not responding
- Autofocus settings inappropriate for sample

**Solution:**
- If only a few positions: Normal, tissue detection working correctly
- If all positions: Check Z-stage in Micro-Manager, verify autofocus config

#### "Background acquisition error"

**Meaning:** Couldn't collect background images

**Likely causes:**
- Camera not responding
- Rotation stage stuck (for PPM)
- Server timeout

**Solution:**
1. Test camera in Micro-Manager
2. For PPM: verify rotation stage moves manually
3. Check Python server console for details

### Stitching Errors

#### "TileConfiguration.txt not found"

**Meaning:** Missing coordinate file for stitching

**Likely causes:**
- Acquisition failed before completion
- Temporary folder was deleted prematurely
- File permissions issue

**Solution:**
1. Check Python server completed acquisition successfully
2. Verify temp tiles folder exists and contains TileConfiguration.txt
3. Re-run acquisition if file is truly missing

#### "Failed to create OME-ZARR directory"

**Meaning:** Can't write ZARR output

**Solution:**
1. Check disk space
2. Verify write permissions on output folder
3. Check path length isn't too long (Windows limit: 260 characters)

### Other Errors

#### "No project is currently open"

**Meaning:** Some workflows require an open QuPath project

**Solution:**
- Bounded Acquisition: Creates project automatically
- Acquire from Existing Image: Open or create project first

#### "No annotations found in current image"

**Meaning:** Acquire from Existing Image workflow needs annotations

**Solution:**
1. Draw annotations with rectangle or polygon tool
2. Select annotations before starting workflow
3. Or use Bounded Acquisition if you don't have annotations

---

## Component-by-Component Troubleshooting

### QuPath Extension

**Problem:** Extension doesn't appear in menu

**Check:**
1. Extension .jar file in extensions folder
2. QuPath version 0.6.0-rc4 or newer
3. No error messages on QuPath startup
4. Dependencies installed (tiles-to-pyramid extension)

**Solution:**
- Reinstall both extensions in correct order
- Check console for Java exceptions
- Verify .jar files aren't corrupted (should be ~10MB each)

**Problem:** Menu items are grayed out

**Check:**
1. Configuration file loaded (Edit > Preferences > QPSC)
2. Python server running
3. Project open (for workflows that need it)

**Solution:**
- Load valid configuration file
- Start Python server
- Create or open project as needed

### Python Server (smart-wsi-scanner)

**Problem:** Server won't start

**Check:**
1. Python version (3.8 or newer required)
2. Dependencies installed (`pip install -r requirements.txt`)
3. Port 5000 not in use
4. Micro-Manager running

**Solution:**
```bash
# Check Python version
python --version

# Install dependencies
cd smart-wsi-scanner
pip install -r requirements.txt

# Start server with explicit port
python -m smart_wsi_scanner.qp_server --port 5000
```

**Problem:** Server crashes during acquisition

**Check:**
1. Python console for exception traceback
2. Micro-Manager still responsive
3. Hardware errors (stage, camera, etc.)

**Solution:**
- Read error message in console carefully
- Check if Micro-Manager device is disconnected
- Restart server and try again
- Check logs for hardware communication errors

### Micro-Manager

**Problem:** Micro-Manager won't control hardware

**Check:**
1. Hardware configuration loaded
2. Devices initialized (green lights in device list)
3. No error messages in Micro-Manager console
4. Hardware physically connected and powered

**Solution:**
- Reload hardware configuration
- Reinitialize devices in Tools > Device Property Browser
- Check physical connections
- Restart Micro-Manager

**Problem:** Stage moves to wrong positions

**Check:**
1. Stage calibration in Micro-Manager
2. Origin set correctly
3. No hardware errors

**Solution:**
- Re-home stage
- Verify stage coordinates in property browser match physical position
- Check for mechanical binding

### Hardware (Microscope Components)

**Problem:** Camera not acquiring

**Check:**
1. Camera power
2. Camera driver loaded in Micro-Manager
3. Camera settings (binning, exposure, etc.)
4. USB/connection cable

**Solution:**
- Power cycle camera
- Reload camera driver in Micro-Manager
- Test with Micro-Manager Snap Image button
- Check device manager (Windows) for camera recognition

**Problem:** Rotation stage not moving (PPM)

**Check:**
1. Rotation stage controller powered
2. Stage position readable in Micro-Manager
3. No mechanical binding
4. Controller communication

**Solution:**
- Power cycle controller
- Check cable connections
- Manually move stage via controller
- Verify Micro-Manager can query position

---

## Configuration Validation

### Microscope Config File (config_ppm.yml)

**Essential settings to verify:**

```yaml
microscope_settings:
  name: "Your_Microscope_Name"
  socket_port: 5000                    # Must match Python server

stage_limits:
  x_min_um: 0                          # Adjust to your stage
  x_max_um: 200000
  y_min_um: 0
  y_max_um: 150000

camera:
  width_pixels: 2064                   # Match your camera
  height_pixels: 1544
  pixel_size_um: 0.325                 # CRITICAL for coordinate calculation

modalities:
  ppm_20x:                             # Must match objective in GUI
    detector: "LOCI_DETECTOR_..."      # Must exist in resources
    objective: "LOCI_OBJECTIVE_..."    # Must exist in resources
    angles: [90.0, 0.0, 7.0, -7.0]    # Your PPM angle sequence
    exposures: [50, 50, 50, 50]        # Match angles list length
```

**Common mistakes:**
- Mismatched angles and exposures list lengths
- Wrong pixel_size_um (causes coordinate errors)
- Misspelled detector/objective references
- Port mismatch between config and server

### Resources File (resources_LOCI.yml)

**Structure:**
```yaml
detectors:
  LOCI_DETECTOR_ANDOR_ZYLA:
    name: "Andor Zyla"
    fov_width_um: 1416.1424            # Width of camera FOV
    fov_height_um: 1060.1104           # Height of camera FOV

objectives:
  LOCI_OBJECTIVE_OLYMPUS_20X_POL_001:
    name: "20x Polarization"
    magnification: 20
    na: 0.45
```

**Common mistakes:**
- FOV dimensions don't match camera + objective combination
- Missing entries referenced in main config
- Incorrect magnification values

### Autofocus Config (autofocus_PPM.yml)

**Per-objective settings:**
```yaml
- objective: LOCI_OBJECTIVE_OLYMPUS_20X_POL_001
  n_steps: 20                          # Number of Z-steps in search
  search_range_um: 30.0                # Total Z search range
  n_tiles: 2                           # Autofocus every N tiles
  texture_threshold: 0.005             # Tissue detection sensitivity
  tissue_area_threshold: 0.2           # Minimum tissue coverage
```

**Common mistakes:**
- n_tiles too low (autofocus too often, slow)
- search_range_um too small (doesn't find focus)
- texture_threshold too high (skips tissue positions)

### Validation Checklist

Run through this before first use:

- [ ] All YAML files have valid syntax (use YAML validator online)
- [ ] Detector and objective references match between files
- [ ] Camera FOV calculated correctly: pixel_size x width/height
- [ ] Stage limits set to physical range
- [ ] Port 5000 available and not firewalled
- [ ] Modality names match what you select in GUI
- [ ] Exposure times reasonable for your samples (50-1000ms typical)
- [ ] Autofocus parameters tested on sample slides

---

## Log Files and Diagnostics

### Where to Find Logs

**QuPath Logs:**
- Primary: `<QuPath-Directory>/logs/qpsc/qpsc-acquisition.log`
- Older logs: Same folder, dated files kept for 7 days
- Project-specific: `<Project-Folder>/acquisition.log` (when workflows enable it)

**Python Server Logs:**
- Console output (not saved by default)
- To save: redirect output when starting server
  ```bash
  python -m smart_wsi_scanner.qp_server > server.log 2>&1
  ```

**Micro-Manager Logs:**
- Micro-Manager console window (not saved by default)
- Enable logging in Tools > Options if needed

### Reading QuPath Logs

**Log format:**
```
14:23:45.123 [Thread] LEVEL  Logger.Class - Message
```

**Important levels:**
- **ERROR:** Critical failures - read these first
- **WARN:** Problems that don't stop execution - check these second
- **INFO:** Normal operation - helpful for timing and progress

**What to look for:**

1. **Error messages** with stack traces (Java exceptions)
2. **WARN messages** before failures (often indicate root cause)
3. **Timestamp gaps** (system waiting or hung)
4. **Repeated messages** (stuck in loop or retry)

**Common log patterns:**

**Successful acquisition:**
```
14:23:45 INFO  BoundedAcquisitionWorkflow - Starting acquisition for SampleName
14:23:46 INFO  TilingUtilities - Generated 150 tile positions
14:25:30 INFO  UIFunctions - Acquisition completed successfully
14:25:35 INFO  StitchingHelper - Stitching 150 tiles...
14:28:12 INFO  TileProcessingUtilities - Successfully imported stitched image
```

**Failed connection:**
```
14:23:45 INFO  MicroscopeController - Connecting to localhost:5000
14:23:55 ERROR MicroscopeSocketClient - Connection refused
```
**Fix:** Start Python server

**Hardware error:**
```
14:25:30 INFO  MicroscopeSocketClient - Querying XY position
14:25:30 ERROR MicroscopeHardwareException - XY stage not initialized
```
**Fix:** Initialize stage in Micro-Manager

**Coordinate transformation:**
```
14:26:15 INFO  TilingUtilities - Pixel size: 0.325 um/pixel
14:26:15 INFO  TilingUtilities - Converting QuPath pixels to stage microns
14:26:15 INFO  TilingUtilities - QuPath (1000, 2000) -> Stage (325.0, 650.0) um
```
Verify pixel size is correct for your setup

### Enabling Debug Logging

**For more detailed logs:**

1. Find QuPath's `logback.xml` (in extensions .jar or QuPath installation)
2. Change log level:
   ```xml
   <logger name="qupath.ext.qpsc" level="DEBUG" additivity="false">
   ```
3. Restart QuPath

**Debug logs show:**
- Detailed socket communication
- Coordinate calculations step-by-step
- Configuration file parsing
- Intermediate workflow states

**Warning:** Debug logs are VERY verbose - use only for troubleshooting specific issues

### Common Log Messages Explained

#### "Stage XY position: (x, y)" [repeated many times]

**Meaning:** Health check heartbeats - normal operation

**This appears during:** Long acquisitions while checking server is alive

**Action:** None needed - this is filtered from main log but might appear in debug log

#### "Tissue stats: texture=X, area=Y"

**Meaning:** Autofocus tissue detection results

**If texture very low (< 0.005):** No tissue detected, autofocus deferred

**Action:** Normal if occasional. If frequent, lower texture_threshold in autofocus config

#### "Found X existing OME-TIFF files before stitching"

**Meaning:** Tracking files in output folder before adding new stitched images

**Action:** None - informational only

#### "Adjusting exposure: Xms -> Yms"

**Meaning:** Adaptive exposure changing camera exposure

**Action:** Normal during background collection or adaptive acquisition

---

## Getting Help

If this guide doesn't solve your problem:

1. **Collect information:**
   - What were you trying to do?
   - What happened instead?
   - Any error messages (exact text)?
   - QuPath log file (last 50-100 lines around the error)
   - Python server console output
   - Screenshot of error dialog if applicable

2. **Check versions:**
   - QuPath version
   - QPSC extension version
   - Python version
   - Micro-Manager version

3. **Try these first:**
   - Restart QuPath
   - Restart Python server
   - Restart Micro-Manager
   - Reboot computer (really - Windows USB/driver issues sometimes need this)

4. **Report issues:**
   - GitHub: https://github.com/uw-loci/qupath-extension-qpsc/issues
   - Include all info from step 1
   - Steps to reproduce the problem
   - What you've already tried

---

## Appendix: Quick Reference

### Essential Commands

**Start Python Server:**
```bash
cd /path/to/smart-wsi-scanner
python -m smart_wsi_scanner.qp_server
```

**Test Microscope Connection:**
QuPath: Extensions > QPSC > Utilities > Server Connection Settings

**Check Logs:**
QuPath: Help > Show log file location

**Reload Configuration:**
QuPath: Edit > Preferences > QPSC > Browse to config file, restart QuPath

### File Locations Summary

| Item | Location |
|------|----------|
| QuPath extensions | `<QuPath>/extensions/` |
| QPSC logs | `<QuPath>/logs/qpsc/` |
| Config files | Your configured location |
| Project data | `<ProjectsFolder>/<SampleName>/` |
| Stitched images | `<Project>/SlideImages/` |
| Temp tiles | `<Project>/TempTiles/` (auto-deleted) |

### Common Port Numbers

| Service | Port | Configurable? |
|---------|------|---------------|
| Python server | 5000 | Yes (in config) |
| Micro-Manager | N/A | N/A |
| QuPath | N/A | N/A |

### Workflow Decision Tree

```
Do you have an overview image of your sample?
+-- YES -> Use "Acquire from Existing Image"
|          Draw annotations on overview
|          Acquire high-res at annotated regions
|
+-- NO  -> Use "Bounded Acquisition"
           Specify region by coordinates or size
           Creates new project automatically
```

---

**Document Version:** 1.1
**Last Updated:** December 16, 2025
**GitHub:** https://github.com/uw-loci/qupath-extension-qpsc
