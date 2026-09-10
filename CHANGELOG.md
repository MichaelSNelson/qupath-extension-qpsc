# Changelog

All notable changes to the QPSC QuPath Extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

**LC-PolScope liquid-crystal calibration workflow**
- New menu item "Calibrate Liquid Crystals..." appears under the QP Scope menu when using an LC-PolScope microscope. Finds the extinction point (where the crystals transmit the least light) and the swing states either side, then writes the palette the acquisition uses. Runs on a clear, specimen-free field; automatically uses the microscope configuration's swing, scheme, and wavelength settings so the calibration cannot disagree with the acquisition. Reports an extinction ratio as a quality figure (100 or above is good); supports single-pass or iterative search strategies.

**Focus Approach Validation: live vertical focus-metric plot during the traverse**
- The focus-approach validation window now displays a vertical plot of the focus metric while scanning from the safe Z toward the sample. The plot is oriented with the traverse start at the top (retracted) and the far end at the bottom (toward sample), matching the stage's direction of motion. During the scan, the Live Viewer's per-frame focus samples accumulate as a live trace (yellow); when the scan returns, the server's actual measurements replace the live approximation with the server's profile (cyan), named for the metric it used (e.g., `brenner_gradient`, `p98_p2`). Reference lines show your hand-focused Z (green dashed) and the current stage Z (gray). The validation window stays open after completion with a "Continue" button so you can review the curve before proceeding to the second (bare-slide) scan.

**Stage Map: slide placement updates as alignments are collected**
- During multi-slide batch acquisition, slides now appear on the Stage Map at their measured actual position once their alignment is saved, rather than staying at their nominal slot centre. A slide's true position in the holder can differ from its nominal slot by a median ~600 µm, so the map's accuracy improves throughout a batch run as alignments accumulate. Before any alignment is saved, slides show at their nominal positions (the existing behavior); after alignment, each slide moves to reflect reality. Only each slide's position is corrected, not its rotation or size, so the map remains an approximate spatial reference rather than an exact one.

**Bug reporter: optional contact fields, and an honest note about anonymity**
- Reports are filed by a shared reporting service, not from your GitHub account, so GitHub has
  nobody to notify -- you would never see a reply unless you went back and commented yourself.
  The dialog now says this at the top, before you write anything.
- Two optional contact fields let you stay in the loop: a **GitHub username** (mentioned on the
  issue, which does notify you) and an **image.sc forum username** (linked to your profile).
  "alice", "@alice" and a pasted profile URL are all accepted; leave both blank to stay anonymous.
  Both appear publicly in the issue.
- New **"Open the issue in my browser after submitting"** checkbox, on by default.

### Fixed

**Multi-Slide: stage controls now lock during slot-jump autofocus, with a Cancel affordance**
- When autofocus-on-slot-jump ran during a multi-slide alignment, the Live Viewer's stage-movement controls stayed enabled, so the operator could bump the stage mid-scan, and there was no way to cancel the scan. The slot-jump autofocus now locks the Live Viewer's stage-movement controls (arrows, joystick, go-to-centroid, live toggle) while it runs, and turns the Live Viewer Autofocus button into a "Cancel Autofocus" toggle -- the same affordance as a single-slide scan. Clicking it sends `ABORTAF` (aborts both the streaming and sweep paths); the controls unlock automatically when the scan settles (success, failure, or cancel). The same lock covers the multi-tile refinement's per-point autofocus, which shares the slot-jump path.

### Changed

**Multi-Tile refinement: "Select tile" becomes "Select a new tile" after the first, and re-picking a used tile is rejected**
- In the multi-tile alignment refinement, the reference-tile button is relabeled to "Select a new tile" once the first point is captured, and selecting a tile that was already captured for an earlier point is now rejected (a "Tile already used" notification; the operator re-picks a different tile). Tiles are matched by their stable `TileNumber` measurement, falling back to object identity.

## [0.10.0] - 2026-08-11

### Added

**MDA export: custom tile field of view**
- Clicking "Save MDA..." (or the per-modality "Save as MicroManager MDA..." button) now opens a small prompt for the tiling field of view, pre-filled with the camera FoV for the selected objective/detector. Accepting the default reproduces the acquisition's own tiling; entering a different width/height tiles the annotations for another device's field -- the motivating use case is handing the position list to the MicroManager laser scanner, whose scan field differs from the camera. An "apply tile overlap" checkbox reuses the current overlap preference or sets 0%. This affects the exported `.pos` positions only; the real camera acquisition is unchanged. Cancelling the prompt aborts the export silently.

## [0.9.3] - 2026-08-11

### Fixed

**"Save as MicroManager MDA..." button: exported .pos now carries real stage coordinates**
- The pre-acquisition MDA export button (on the Existing Image Acquisition modality panel) synthesized tile centroids in the open image's full-resolution pixel space, then applied either a saved **macro -> stage** preset (which maps ~81 um/px macro pixels, not ~0.25 um/px full-res pixels -- off by ~300x) or, when no preset was selected, raw `pixel * pixel-size` with no stage offset. The resulting `.pos` coordinates matched no real stage position. The button now uses the same **full-resolution -> stage** transform the acquisition itself uses (`MicroscopeController.getCurrentTransform()`), so the exported positions match what the acquisition process writes. If no such transform is available yet (alignment not run), and no already-full-res-scale preset is selected, the export now reports "run alignment first" instead of writing miscalibrated coordinates.

## [0.9.2] - 2026-08-11

### Fixed

**Multi-Slide: a slide assigned to a new slot no longer reverts to its old position**
- In the Multi-Slide assignment dialog, the same project image could be selected in two slots at once -- e.g. it was pre-filled at slot 5 (from a prior run's `slide_position`) and then re-picked at slot 4 without the leftover slot-5 selection clearing. On start, the run stamped that base entry once per slot, and because the slots stamp in order the **later slot won**, silently reverting the assignment (the "I set it to slot 4 but it keeps defaulting to 5" report). The dialog now enforces one slide per slot: selecting an image in a slot clears it from any other slot, and on start a base entry is stamped with exactly one `slide_position` (first slot wins if a duplicate ever slips through). Existing projects self-heal on the next correct run.

## [0.9.1] - 2026-08-11

### Fixed

**Multi-Slide (PPM): base annotations now copied onto the rotated "(Camera View)" companion**
- After the 0.9.0 orientation refactor, a multi-slide slot's rotated/flipped working entry ("X (rotated 270) (Camera View)") was built directly and opened without passing through the single-slide base->companion annotation mirror, so it started empty -- the operator's macro annotations never reached the acquisition entry, and the workflow took the "no annotations" branch. The root cause was that the multi-slide entry-creation path (`createRotatedFlippedDuplicate`) never stamped the new light-path metadata (the `camera_view` flag and baked parity), because `applyImageMetadata` no longer records flip. The fix: (1) the multi-slide path now stamps the full light-path snapshot on the companion at creation (matching the single-slide path); (2) a pre-dialog step brings the source macro's annotations onto an empty companion -- transformed through the same rotate+flip that produced its pixels, then persisted -- **before** the acquisition dialogs read its annotation classes.

### Changed

**Rotation is now recorded in metadata, not read from the entry name**
- The baked quarter-rotation of a companion is stored in `lp_rotation_deg` metadata and is the single source of truth the code reads. The "(rotated N)" name token is retained for user reference only; no code path parses the name for rotation (or for camera-view classification) any more -- rotation comes from `getRotationDegrees(entry)` and companion classification from `isCameraView(entry)`. Rotated-companion lookup (single- and multi-slide) now matches on `base_image` + baked rotation + the `camera_view` flag rather than on the composed name. The `(rotated N)(flipped ...)` name-based transform math is consolidated into one shared implementation (`AnnotationHelper`) used by both the existing-alignment gate and the new multi-slide populate.

## [0.9.0] - 2026-08-11

### Changed

**Light path & orientation: consolidated model, per-slide placement, "(Camera View)" companion**
- The scattered flip/orientation logic is consolidated into a single cohesive, forkable `LightPath` class (new `utilities/lightpath` package: `LightPath`, `Parity`, `LightPathSnapshot`), which composes the orientation stack per detector and is the one place a differently-configured scope plugs in its geometry.
- **Per-slide placement.** A slide can now be placed either way in the holder (label-left "as scanned" or label-right, a 180). The Stage Map "Slide:" control now stores this **per slide** and bakes it into the corrected whole-slide image so that image matches the live camera for the placement actually used. Changing placement marks the corrected image and any saved alignment stale (re-run alignment).
- **The corrected companion is renamed to a single "(Camera View)" entry** (was "(flipped X|Y|XY)"). The flip axes are recorded in metadata rather than the name; check the entry's light-path metadata for specifics.
- **`flip_x`/`flip_y` metadata removed.** Per-entry orientation was a lossy single-bit proxy; it is replaced by the full light-path metadata set plus a derived net "baked parity". This is an internal change with no effect on existing as-scanned slides (locked by regression tests), but it is **not backwards compatible**: projects with old "(flipped X)" entries will have a fresh "(Camera View)" companion created on next alignment.

### Fixed

**Multi-Slide & Existing Image Acquisition: removed hard requirement for macro pixel size preference**
- The Existing Image Acquisition workflow (including Multi-Slide) previously required the "Macro image pixel size in microns" preference to be set, and crashed with "Macro image pixel size is not set" if it was empty. That preference was dead code: once a fullRes->stage alignment transform exists (which is always present in these paths), tiling needs only the camera FOV and the open image's own pixel calibration. The workflow now automatically resolves pixel size from the open image's calibration instead, so users no longer need to set the preference (though leaving it set causes no harm). This fixes the multi-slide batch acquisition blocker that was preventing workflow progress until the preference was manually configured.

**SIFT auto-align: incorrect stage move on flipped entries**
- Auto-Align (SIFT) was moving the stage to the mirror (off by ~1.5 tiles) on flipped entries such as PPM flipped siblings. The offset was being applied raw, which only works for axis-aligned positive-scale transforms. The fix composes the SIFT offset through the same alignment transform used to predict the tile position, so the stage move is correct on any rig by construction -- no hard-coded signs. The raw offset is still shown in the UI for debugging; the stage move uses the composed delta.

### Changed

**Stage Map: per-microscope light path configuration**
- The Stage Map's orientation is now derived from per-microscope YAML configuration (`light_path` block). Three separate dropdowns govern the display: **Scope type** (Upright | Inverted, factor 1a: how the slide physically sits), **Slide insertion** (A | B, factor 1b: which side the label is on), and **Optics** (none / flip X / flip Y / 180 (XY), factor 2: objective + tube-lens parity). **Scope + Slide** determine the Stage View (how the slide sits on the bench); **Optics** feeds the Camera View (so the map matches the Live Viewer). These are display only; acquisition already gets the orientation right via the empirical alignment transform. Changes write directly to the microscope config (`light_path.scope_type`, `slide_insertion`, `optical_flip`), so the Stage Map and Setup Wizard share one source of truth. Defaults (Upright + A + Optics: none) reduce both views to identity — the historical inert behaviour, no regression until the rig is described.

**Live Viewer Camera tab: Profile default now matches current objective**
- The Profile dropdown in the Camera tab now defaults to the profile matching the currently-active objective (e.g., showing `Brightfield_20x` when the objective is 20x), using the same resolution rule as acquisition workflows. Previously the dropdown defaulted to the first profile in the list regardless of objective, which could show `Brightfield_10x` on a 20x objective and require a manual selection change. A user's manual selection is still retained across rebuilds (e.g., when switching modalities).

### Added

**Background image missing: non-blocking warning notification**
- When acquisition begins and one or more angles lack a matching background image, a warning toast now notifies the operator which angles will not have background subtraction applied. The acquisition proceeds automatically (the toast is advisory, not a prompt); the operator can let it run or cancel and re-collect backgrounds. Previously the disabling was silent.

**MicroManager Folder Stitch: axis inversion controls**
- New checkboxes **"Invert X axis"** and **"Invert Y axis"** in the Stitch MicroManager Folder dialog allow correcting for stage-axis inversion that occurs when a microscope's MicroManager configuration inverts an axis. When inverted, MicroManager records stage positions that run opposite to pixel space, causing tiles to be laid out mirrored; negating that coordinate before stitching corrects the mirroring. Checkboxes default to the current microscope's stage polarity (for the common case of stitching data just acquired on that same scope) and can be overridden to match different hardware configurations when stitching data from a different microscope. User choices are remembered across sessions.

## [0.8.0] - 2026-07-22

### Changed

**QuPath 0.7.0 is now required**
- The extension declares and builds against QuPath 0.7.0 (was 0.6.0). Install QuPath 0.7.0 or later. Docs updated to match.

**Multi-Slide Acquisition promoted out of experimental**
- The former "MS-Existing Image (experimental)" workflow is now **"Multi-Slide Acquisition..."**, shown directly beneath "Acquisition Wizard..." at the top of the QP Scope menu. The opt-in "Enable Multi-Slide Workflow (experimental)" preference has been removed: the menu entry appears automatically whenever the active microscope config defines a multi-slide holder (a `slide_holder` insert with more than one slot) and is hidden otherwise. Launching it now auto-opens the Live Viewer and Stage Map (like the Acquisition Wizard) so the stage/insert context is visible while assigning slides to slots.

### Added

**"Restart QuPath" advisory after an install or update**
- When the extension jar is installed or updated in a running QuPath session, the old classes stay loaded until a restart -- which can look like the update did not take effect. On the first launch after the packaged version changes, a one-time modal now advises restarting QuPath. It does not appear on normal relaunches, and unpackaged IDE/dev builds are skipped. (Added to QP-CAT and the DL Pixel Classifier as well.)

### Fixed

**Stage Map: multi-slide macro previews lingered after switching inserts**
- After a multi-slide run, the rotated per-slot macro previews (e.g. a vertical `quad_v` layout) stayed on the Stage Map when you manually switched the insert dropdown back to a single-slide holder, so the macro appeared "still vertical" over the wrong insert. The previews are now cleared when the Stage Map is switched to a different carrier (re-selecting the same carrier keeps them as a layout reference). No flip/rotation math changed.

**Multi-Slide assignment: diagnostic logging for eligible-macro selection**
- The assignment dialog now logs each project entry it considers -- name, `base_image`, source scanner, and the include/exclude reason -- plus a final eligible-of-total tally, so a "no eligible macro entries" result is diagnosable from the log instead of silent.

## [0.7.2] - 2026-07-22

_Bundles all work merged to `main` since 0.7.1 (the items below), plus the stitching fix._

### Fixed

**Stitching: NPE crashed the stitch after a completed acquisition (null transform)**
- Stitching runs asynchronously after an acquisition finishes. `AcquisitionManager.launchStitching` was passing the *live* microscope transform (`MicroscopeController.getCurrentTransform()`) into the async stitch, but the workflow's cleanup nulls that transform (`setCurrentTransform(null)`) once the run winds down. On a long acquisition the cleanup won the race, so the stitch-metadata step dereferenced a null transform and threw a `NullPointerException` -- failing the stitch *after* the tiles had been fully acquired. The fix passes the immutable transform captured in the workflow state (the same value every other site in `AcquisitionManager` uses), and adds a defensive null guard so a missing transform degrades to a zero metadata offset instead of crashing an already-completed acquisition's stitch. The acquired tiles on disk are unaffected; only the automatic stitch+import was lost, and can be re-run on the tile folder without re-acquiring.

### Added

**Multi-Slide Existing Image: auto-collapse progress panel on focus loss**
- The progress panel in the Multi-Slide Existing Image workflow now automatically collapses when focus shifts to another window (e.g., an alignment dialog or the Stage Map) and automatically expands when the panel regains focus. This mirrors the Acquisition Wizard's behavior, letting the panel tuck itself out of the way during alignment steps without a manual click. The manual **Collapse** button still works as before.

### Fixed

**Bug reporter: issue titles came from the first line of the description**
- The "Report a bug" dialog now has a required one-line **Summary** field, which becomes the GitHub issue title. Previously the shared Cloudflare Worker derived the title from `description.split("\n")[0]`, so a report that opened with a greeting filed an issue titled `[bug] Hi,` -- and nothing in the dialog hinted that the first line mattered. Surfaced by `uw-loci/qupath-extension-cell-analysis-tools#8`. The Worker (`cloudflare-worker-bug-reporter/src/index.js`) now prefers a `summary` field and falls back to the old first-line behaviour, so jars that predate this change keep working. The same field was added to the QP-CAT and DL Pixel Classifier reporters. **The Worker must be redeployed for the new field to take effect.**

### Added

**High-bit-depth PPM capture (opt-in)**
- New PPM preference **"High Bit Depth PPM Capture"** (default OFF). When enabled, the PPM angle frames are acquired at the camera's higher-bit PixelFormat so the birefringence image is computed from genuinely high-precision inputs instead of 8-bit. The `.biref` file has always been 16-bit, but it was fed by the JAI's 8-bit output, so it carried only ~8 bits of real information; because the normalized birefringence `|(I+ - I-)/(I+ + I-)|` is scale-invariant, higher-bit inputs do not change the result's shape -- they reduce quantization noise, most visibly in the dark crossed-polarizer regions. The birefringence file stays 16-bit; QuPath's display maps it to the screen. OFF is byte-identical to the previous 8-bit path (no wire flag emitted). Requires a `high_bit_depth` block on the camera's detector entry in `resources_LOCI.yml` (MM PixelFormat property + high value, discovered per-scope); absent, the preference is a safe no-op. Spans the Java extension (`--ppm-high-bit-depth` flag), the command server (per-tile format switch with guaranteed restore, autofocus stays 8-bit), and `ppm_library` (dtype-aware dark-mask + sum normalization). See `documentation/developer/PPM_MODALITY.md`.

## [0.7.1] - 2026-07-06

### Added

**Petri-dish calibration by coverslip corners**
- Coverslipped 35mm dishes are now calibrated by the **four corners of the square coverslip** instead of the circular well edges. A circle has no rotational reference, but the coverslip corners are real fiducials and easy to center in the FOV. In Stage Map > Calibrate..., dishes show four coverslip-corner rows (one "Capture corner" button takes X and Y together, any order) plus a wireframe schematic (dish / coverslip / well) that highlights the corner being captured. The imaging rectangle is the bounding box of the four corners; axis inversion comes from the Stage Polarity setting (so place the dish in the scope's usual orientation). Config schema: `coverslip_c1..c4_x_um/_y_um` (+ `coverslip_size_mm`) on a `dish_holder` insert; the registry injects the aperture rectangle, so the well circle and dish outline still render. Free re-orientation (rigid solve + rotated overlay) remains a deferred follow-up.

**SIFT alignment: focus/exposure warning**
- Every SIFT alignment dialog (microscope-to-microscope alignment, existing-image manual alignment, and sub-acquisition refinement) now shows a bold reminder to confirm the live image is in focus and not saturated before aligning. SIFT matches on image detail, so a blurry or blown-out frame misaligns or fails -- and this is not detected automatically. Centralized in `SiftAutoAlignHelper.buildFocusSaturationWarning()`.

**Register Current Objective utility** (QP Scope > Utilities)
- New menu item to add the objective currently in the light path to the microscope config. It reads the pixel size from MicroManager (the one thing MM knows about an objective -- there is no MM magnification/NA/name), then prompts for the metadata MM cannot provide (id, display name, magnification, NA, working distance, detector). It writes skeleton entries across all the config files (`hardware.objectives` pixel size, an `autofocus_settings` block with `calibrated: false`, `imaging_profiles` placeholders per modality, and the `id_objective_lens` display entry in `resources_LOCI.yml`), then reloads the config so the objective is immediately selectable. Calibration-heavy data stays a placeholder -- the summary points to the Autofocus / White Balance / Background utilities to finish. All writes are comment-preserving (new `ConfigYamlEditor.appendListItem` / `appendMapEntry` primitives) and idempotent, so re-running after a partial failure is safe. Requires a live microscope connection.

### Fixed

**Existing-image: choose annotation class after tissue detection**
- In the no-annotations path, the warning dialog gated "Use Annotations and Continue" on the pre-selected classes and re-read by those classes, so running tissue detection (which classifies as "Tissue") left the user stuck whenever "Tissue" was not pre-selected -- the detected annotations were filtered out with no way to pick the class that was produced. The dialog now counts any annotation (so detection output lets you proceed), and on confirm the annotation-class selection dialog is shown populated with the classes that now exist; the chosen class filters the hierarchy and is stored so the post-routing re-read does not re-prompt.

**Live Viewer Camera tab: stale hardware -> wrong PPM/BF presets**
- The Camera tab resolved its objective + detector once at Live Viewer open, which can run before the socket's live pixel size is available and fall back to a stale objective -- building the PPM/brightfield presets for the wrong objective+detector (incorrect/single exposures, missing per-channel "Uncrossed (Simple WB)" button). It now re-detects automatically when the Camera tab is selected (change-aware: only rebuilds when the objective/detector actually moved), so presets reflect the real hardware without a manual "Refresh from MM" click.

### Documentation

- Clarified that PPM sunburst calibration (hue -> angle) is **objective-independent**: a calibration captured at one objective (e.g. 40x) is valid at any other (e.g. 20x), higher magnification only improves quality. A magnification in the calibration filename/metadata records where it was captured, not a mismatch. (Active Calibration File preference tooltip + `PPM_MODALITY.md`, which also warns against re-adding an objective gate to the stamping.)

## [0.7.0] - 2026-06-25

### Added

**Stage Map**
- The SIFT search-range box now follows the live stage crosshair during a refinement step instead of staying at a fixed center, so the square tracks the objective position as you nudge the stage; it is removed when the alignment step ends. (`StageMapCanvas` follow mode + `StageMapWindow.setSearchRangeFollow`.)
- Click-to-move into the **dish body** is now allowed for petri-dish inserts (any insert with `dish_diameter_mm` set): inside the imaging well moves freely, moving out into the dish body is allowed with a once-per-session caution (needed to calibrate the well edge), and moves past the dish outline are blocked. Plain slides keep the strict aperture gate.

**Acquisition**
- Pre-acquisition warning when the flat-field background was collected at a different lamp/illumination level than the acquisition profile about to be used (the silent cause of per-tile intensity/vignetting seams in brightfield). Compares the profile `illumination_intensity` against the level recorded in `background_settings.yml` and shows a cancelable dialog before the stage locks; scoped to adjustable-lamp collections (`lamp.available: true`). (`BackgroundIlluminationCheck`.)

**Stage direction calibration ("Calibrate Directions" tool)**
- Interactive dialog for calibrating camera orientation (optical flip/rotation) by jogging the stage and observing the Live Viewer image. Available in two places: as an optional Setup Wizard step (Step 5: Stage Calibration) and as a "Calibrate Directions..." button in the Live Viewer's Navigate tab. The dialog physically moves the stage by a small step in +X then +Y, asks which direction the image appeared to pan, and solves for the `Camera orientation` that matches under the user's current `Inverted X/Y stage` polarity setting — polarity is treated as a hardware-wiring fact (verified via direct stage observation or the MicroManager-script procedure in PREFERENCES.md) and is not changed by the tool. Non-blocking: saves your start position and restores it when closing. Manual override panel allows editing both polarity and orientation together for advanced users. Implementation: `StageDirectionCalibrationDialog` with 8-orientation back-solve over a fixed polarity, `StageCalibrationStep` in the Setup Wizard, and `Calibrate Directions...` button in `StageControlPanel`.

**MicroManager MDA export**
- Every QPSC acquisition now auto-writes a MicroManager-loadable file set into each region folder: `MDA_<region>.txt` (a JSON-serialized `SequenceSettings`), `MDA_<region>.pos` (a `PositionList`), and `MDA_NOTES.txt` (provenance + caveats). The MDA files can be loaded directly into MM 2.0 -- MDA window for the settings, Stage Position List window for the positions -- to re-acquire the same channels/Z/positions in MicroManager (useful for laser-scanning modalities that QPSC doesn't drive directly).
- "Save as MicroManager MDA..." button on the existing-image and bounding-box acquisition dialogs for export-only runs (no QPSC acquisition kicked off).
- PPM exports are positions-only (no MM ConfigGroup equivalent for polarization angles); `MDA_NOTES.txt` documents the mismatch. Autofocus is marked off in the MDA file because QPSC's per-tile streaming AF doesn't map to MM's per-position AutofocusManager. New `mm_stage_devices:` YAML block lets you pin the MM device labels for XY/Z stages (falls back to `"XYStage"` / `"ZStage"` with a warn-log when omitted).

**Multi-dimensional progress panel**
- A new panel beneath the acquisition progress bar shows the active plan summary (tiles | channels | Z | T | total) and live per-axis counters (Channel: FITC, Z 3/9, Tile 47/84) inferred client-side from the running tile index via `LiveDimensionDecomposer`. A hidden time-lapse progress bar lights up when timepoints > 1. Drift sentinel collapses the per-axis labels to an italic notice if the inferred state ever drifts from server reality; the aggregate bar continues unaffected.

**Stitch MicroManager Folder**
- Standalone workflow for stitching MicroManager 2.0 OME-TIFF tile folders without a QuPath project. Reads tile positions from MMStack `*_metadata.txt` sidecars, auto-detects pixel size from metadata or TIFF resolution tags, and outputs a single stitched OME-TIFF or OME-ZARR. Preserves channel names, exposure, and MicroManager acquisition metadata in a `.mm-metadata.json` sidecar. Access via Extensions > QP Scope > Utilities > Stitch MicroManager Folder...

**Live Viewer**
- **Snap button**: Capture and save the current live frame as an OME-TIFF with embedded metadata (stage position, pixel size, modality, objective, detector). Right-click context menu for options: save raw bit depth (default ON), apply background correction (default OFF, greyed out unless settings match), open file after save (default OFF), and reset save folder to project.
- **Window title** now shows the current modality and expected objective magnification, e.g. `Live Viewer (Brightfield) (10x)`. Updates live when the Camera-tab modality dropdown changes or the objective is re-detected; degrades to `Live Viewer` when either value is unknown.
- **Cancellable autofocus**: while a streaming autofocus scan is running, the **Autofocus** button becomes a **Cancel Autofocus** button. Clicking it aborts the scan -- the server stops between frames, restores the camera ROI and stage speed, and returns Z to the position autofocus started from. New `ABORTAF` socket command (sent on the auxiliary socket since the primary is blocked inside the STRMAFZ scan); the scan replies `ABORTED` and the button resets without error styling. Best-effort against older servers: a 3 s ACK timeout degrades the cancel to a silent no-op rather than freezing frame polling.

**Socket protocol**
- `CORRECTFRAME` command: Request a flat-field-corrected frame from the server using the configured background settings for the current modality, objective, detector, WB mode, and rotation angle. Same wire format as `GETFRAME` on success; returns `FAILED:<reason>` on configuration mismatches.

**Channel Picker (Widefield IF, BF+IF)**
- **Named presets**: Save, load, and delete named snapshots of channel configurations (selected channels, exposure, intensity, focus channel). Presets persist across QuPath sessions and are listed in a dropdown for quick recall.
- **Test Current Channel**: Apply a single selected channel's hardware state to the microscope and open Live Viewer for verification without starting a full acquisition. Useful for dialing in exposure and intensity settings before multi-tile acquisition.

**Bounded Acquisition**
- **Time-lapse acquisition**: Collapsible **TIME-LAPSE OPTIONS** pane in the acquisition dialog. Enable to repeat the full region acquisition over multiple timepoints at a fixed interval. Includes spinner controls for number of timepoints and interval (seconds between timepoint starts). If a timepoint exceeds the interval, a one-time "falling behind" warning is shown (modal dialog + push notification if configured) and acquisition continues. The feature is optional and backward-compatible; omitting time-lapse settings yields single-timepoint acquisitions identical to pre-time-lapse builds. New socket protocol commands `REQTWARN` (best-effort, auto-disables against older servers) and ACQUIRE flags `--timepoints` / `--interval`.

**Acquisition Workflow Progress dialog**
- The dimension panel's per-annotation positions fill bar was removed: it duplicated the larger `currentProgressBar` directly under the **Current Annotation Progress** header (both tracked positions through the current annotation, just counting completed vs in-progress -- 6/9 vs 7/9). The `Tile n/N` text label stays for granular position visibility; `timepointProgressBar` stays for the time-lapse axis.
- The time-remaining line is now **bold and slightly larger**, with an **estimated completion clock time** ("Est. completion: Wed 16:05") on the line directly below it.

**Saturation handling**
- When the birefringence saturation guard trips on the initial monitoring tiles, the acquisition now **pauses and prompts** instead of hard-aborting. A modal with a red **Continue anyway** button (acquire despite saturation -- useful for faint signal that needs a higher exposure) and a **Cancel acquisition** button lets the operator decide, and a push notification fires when the prompt appears. Choosing Continue suppresses the saturation guard for the rest of that run; Cancel aborts as before. New `REQSAT` / `ACKSAT` socket commands, best-effort against older servers -- an old `microscope_command_server` hard-aborts on saturation as it did previously. Requires the matching server build for the interactive prompt.
- The acquisition-failure dialog no longer truncates the saturation reason mid-word (the server's `FAILED:` status cap was raised from 250 to 500 bytes).

**Source microscope: active scope is now a valid source**
- The active microscope is a first-class `source_microscope` value: it appears at the top of the **Stage Map** source dropdown and is the default when the open entry has no source tag. `source == target` resolves to identity (no preset, no flip). Genuine cross-scope work (e.g. `Ocus40 -> OWS3`) keeps its existing pair-preset path unchanged.
- **Source-mismatch warning** at the Existing Image workflow start: when the open entry's `source_microscope` disagrees with the active microscope, a dialog offers **Fix source to `<active>`** (update the tag and treat as native), **Proceed (cross-scope)** (keep the source and use its saved alignment), or **Cancel**. The body calls out when the tag is internally inconsistent (`acquired_on_microscope == active` but `source != active`) and should be corrected.
- See `documentation/developer/COORDINATE_TRANSFORMS.md` § "Active microscope is a valid `source_microscope`".

**Make Project Portable**
- Now deletes the raw individual tile images (the per-mode acquisition folders alongside `SlideImages`) by default after swapping ZARR-backed entries to TIFF, since those tiles are only needed to re-stitch. A **Keep individual tile images** checkbox preserves them. The dialog shows the tile count and size, warns that the deletions are permanent, and asks for confirmation before doing any work. Acquisition metadata files inside the tile folders are preserved -- only raw tile images are removed.
- **ZARR handling selector**: the dialog now offers three independent ZARR actions instead of always converting. *Convert ZARR to OME-TIFF* (default) converts ZARR files that have no `.ome.tif` yet on the spot (via `StitchingHelper.convertSingleZarrToTiff`) instead of leaving them stuck as `MISSING` -- this fixes the case where re-stitch recovery in OME_ZARR mode produced ZARR images that Make Portable could not act on, blocking the whole operation. *Zip ZARR to .ome.zarr.zip archive* zips each `.ome.zarr` directory to a sibling archive and deletes the directory (the archives must be extracted back to `.ome.zarr` before the project is reopened -- QuPath cannot read a zipped ZARR; the dialog and confirmation warn about this). *Leave ZARR untouched* skips ZARR entirely, so the tile-deletion checkbox can be used on its own to only clean up raw tiles. The warning text and the **Make Portable** button enablement now follow the selected options rather than being all-or-nothing on whether every TIFF already exists.

### Changed

**Z-Stack / Time-Lapse dialog (legacy removal)**
- Removed the legacy `StackTimeLapseWorkflow` dialog and the "Use new Z-Stack / Time-Lapse dialog" preference (`qpsc.experimental.singlePointDialog`) that gated the fallback to it. The Utilities > Z-Stack / Time-Lapse menu now always routes to the unified Single-Point Acquisition dialog (the default since 0.6.0). The unused `qpscStackTimeLapseProfile` / `qpscStackTimeLapseChannel` preferences were dropped as well.

**Stitching concurrency: parallel angles and channels (both OME-TIFF and OME-ZARR)**
- Angles (PPM) and channels (fluorescence) within a single annotation now stitch in parallel, bounded by the **Stitching concurrency** preference (default: 4 concurrent writers). Previously, OME-TIFF required sequential stitching due to BioFormats' `OMEPyramidWriter` concurrency bug; this constraint no longer applies with the new `DirectTiffOutputWriter`. Both OME-TIFF and OME-ZARR now use the same bounded-pool approach: each angle/channel is an independent writer to its own output file, and higher concurrency values accelerate multi-angle PPM and multi-channel fluorescence acquisitions. Set to 1 in **Preferences** for fully sequential stitching if needed for memory or isolation reasons. Does **not** introduce cross-annotation parallelism: different annotations continue stitching one at a time (their acquisition is inherently sequential as the stage moves between regions), so stitching of one annotation overlaps acquisition of the next without unbounded work accumulation.

**Background Collection: three-mode exposure selector (BF + PPM monochrome)**
- The Background Collection dialog's Acquisition Profile, Starting Exposure, and Target Intensity fields used to be three independent levers that all silently combined into the saved background -- selecting a profile **and** typing a target intensity would have the target override the profile's exposure with no indication, and lab members couldn't tell which lever was driving the saved BG. The dialog now shows a "Exposure mode:" radio group above those fields (for brightfield and PPM on monochrome cameras only) that spells out three explicit modes: **Use profile exposure** (no adaptation; profile drives lamp + exposure), **Target intensity (adaptive)** (no profile binding; the LV Camera tab is assumed to have set hardware; server iterates exposure to hit target), and **Override profile with target** (profile bound for record + lamp, target overwrites profile exposure -- confirmation required at Start, saved YAML records `profile.exposure_overridden: true`). Defaults are PROFILE for brightfield (the user's expected behavior) and TARGET for PPM (preserves the existing adaptive behavior). Choices persist per modality family via new `qpscBgExposureMode.brightfield` / `qpscBgExposureMode.ppm` preferences; documented in `documentation/PREFERENCES.md`. The selector is hidden for fluorescence (per-channel exposures driven by the profile's channel table), for RGB cameras (WB mode drives exposure), and for PPM with WB Simple / Per-angle (per-angle exposures live in the WB calibration). Brightfield users will no longer find their backgrounds running at whatever exposure / lamp / condenser the previous workflow happened to leave behind -- the profile's nominal values now drive the BG snap by default.

**Sweep Autofocus (renamed from "Sweep Drift Check")**
- The acquisition-time focus correction is now called **Sweep Autofocus** everywhere -- TitledPane, **Test Sweep Autofocus** button, status messages, tooltips, log lines, all developer + user docs. Lab members switching between the Live Viewer's autofocus controls and the Autofocus Editor were confused by two different names for the same algorithm.
- The Live Viewer's **Sweep** button now invokes the server-side `TESTADAF` path (same as the Editor's Test button) instead of running its own Java-side step-and-snap loop. This removes the hardcoded `NUM_STEPS=30` that silently overrode YAML `sweep_n_steps`, eliminates the pause/resume-per-step frame-timing race, and unifies the focus metric implementation with the rest of the system. Cancellation during Sweep is no longer supported (TESTADAF has no abort channel); the button reads "Sweeping..." and disables for the ~5-10 s run. The deleted `SweepFocusController` source is preserved in `claude-reports/design/2026-05-24_sweep-focus-controller-removal.md` with notes on which of its safety nets could be ported server-side if equivalent failure modes appear on TESTADAF.

**Autofocus range dropdown: "Auto" -> "Config"**
- The Live Viewer toolbar's focus-range dropdown's "Auto" option is now labeled **Config** to make it explicit that it pulls `sweep_range_um` from the autofocus YAML. The explicit um values continue to override the YAML for **Streaming AF only**; **Sweep Autofocus** always reads from the YAML regardless of the dropdown. Stale "Auto" picks from older builds migrate to "Config" on next open.

**Autofocus Editor labels**
- Snake_case parameter names in the dialog are replaced with human-readable labels (`Z samples`, `Search range (um)`, `Texture threshold`, `Score metric`, etc.); the YAML key is shown in the existing gray description line as `(YAML: foo_bar)` so YAML editors and log readers still find it. The Standard Autofocus section now reads "Search range first, then Z samples" to match the Sweep Autofocus section's order ("how big a window, how finely do I sample it").

**Z-Stack / Time-Lapse dialog: modality + profile + channel picker**
- The dialog now has a shared **Setup** pane above the tabs with Modality / Profile / Channel dropdowns. On **Start**, the dialog calls `applyProfile(profile)` (full mode switch: PMT safety, ConfigGroup presets, detector switch, illumination, intensity, mode positions) and -- for channel-based modalities -- `applyChannel(profile, channelId)` before the existing Z-stack / time-lapse run. Replaces the previous hardcoded `"brightfield"` modality and `null` objective/detector wire arguments. **This fixes the brightfield dynamic-range issue**: with a real BF profile applied, the YAML's exposure / lamp intensity / condenser aperture settings reach the hardware before any tiles are captured. Channel row is hidden for modalities with no channels (BF, PPM). Selections persist across dialog re-opens. Empty `acquisition_profiles` config disables the controls with an inline notice rather than throwing.

### Fixed

**Petri-dish insert: acquired image rendered upside down**
- A `dish_holder` insert derived its Y-axis inversion from `slide_top/bottom_y_um` (which dishes do not set, so they defaulted to `0`/`25000` -> `yInverted=false`) instead of the `aperture_top/bottom_y_um` points the dish actually supplies. On stage-inverted scopes this rendered the dish's acquired-image overlay (and well origin) vertically flipped; the well is a centered circle so the flip was invisible on it. `yInverted` now prefers the aperture Y points, parallel to `xInverted` and the origin computation.

**Existing Image Acquisition: alignment age display and recommendation logic**
- Fixed a bug where scope-namespaced alignment files (e.g., `<sample>_OWS3_alignment.json`) were not detected, causing the "Last refined" date to show as "Unknown" and preventing the age-based confidence calculation from working. The system now checks for scope-namespaced files first, then falls back to legacy unscoped files, matching the behavior of the alignment-loading code.
- Aged alignments (older than ~50 days, low confidence) now default to Single-Tile Refinement instead of Full Manual Alignment. Since an existing alignment is available (the refinement section only appears when "Use existing alignment" is active), a quick single-tile verification is more practical than a full re-alignment from scratch. Full Manual remains always available as an explicit choice.
- Alignment timestamp parsing now handles both `Date.toString()` format (new save sites) and legacy ISO-like format (external or old files), so the "Last refined" banner and age-based recommendations work for either.

**Live Viewer Z-bar focus trace and navigation grid layout**
- Focus-metric trace is now drawn inline in the left margin of both coarse and fine bars, anchoring at the track's left edge and extending leftward proportionally to the Brenner gradient metric. This consolidates the trace into the bar canvases rather than rendering it in a separate column, making the panel more compact.
- Navigation grid (joystick and arrow buttons) now centers correctly in the Navigate tab when the Z Focus widget is widened. The VBox containing the grid now has `fillWidth(false)` so its content respects the tab's `Pos.CENTER` alignment.

**Acquisition Workflow Progress dialog**
- The Total / Current Annotation / positions progress bars were created with a width but no height, so inside the auto-sized dialog they collapsed to zero and no bar ever rendered. All bars now have an explicit height and display correctly.
- The dialog is now resizable and grows (never shrinks) to fit its content, so the **Cancel Workflow** button is no longer clipped off the bottom as the dimension panel, completion clock, and stitching list appear during a run.

**Stage Map (scopes without a `stage.inserts` block)**
- When the microscope YAML has only `stage.limits` and no `stage.inserts` calibration block, the Stage Map synthesizes a single-slide holder from the limits. That synthesized holder assumed non-inverted stage axes, so on a scope with an inverted X (or Y) stage -- e.g. OWS3 -- the whole map was mirrored: double-clicking the middle of the slide drove the stage to the X-flipped position and the crosshair tracked the stage backwards. The synthesized holder now takes axis inversion from the stage-polarity preference, so click-to-move and crosshair tracking match the real stage.

**Stage limits bounds check**
- The stage-bounds check (`isWithinStageBounds`, used before every Stage Map / stage move) did a literal `pos >= low && pos <= high`, so a `stage.limits` block that listed `low` greater than `high` -- natural on an inverted axis -- rejected *every* position with "Target position is outside stage limits". The check now normalizes `low`/`high` with min/max, matching the Stage Map's synthesized insert, which already tolerated either ordering. `stage.limits` must be MicroManager coordinates (see the comment in `config_template.yml`).

**`source_microscope` was default-stamped with the wrong scanner**
- `StageMapWindow.onOpenedImageChanged` (and `pickInitialSource`) used to auto-default the persistent scanner pref onto any opened entry that lacked `source_microscope`. On scopes whose only saved preset is a cross-scope alignment (e.g. OWS3 with only `OWS3_Ocus40_Transform`), the default-fill wrongly tagged native slides with the external scanner. The flip path then used that tag to build a spurious `(flipped X)` sibling and `validateAndFlipIfNeeded` applied an `Ocus40 -> OWS3` optical flip to an image that was already in OWS3's frame. The default is now the **active microscope**; the persistent pref only seeds the alignment workflow's initial scanner pick.
- `ImageFlipHelper.validateAndFlipIfNeeded` now short-circuits when `source_microscope == active microscope` OR `acquired_on_microscope == active microscope`. Belt-and-suspenders: even if some legacy entry still carries a stale external-scanner source tag, an image acquired on the active scope is in the active scope's frame and gets no flip.

**Stage Map Source dropdown: persistent selection + no auto-sync**
- The Preset dropdown now prioritizes the user's last explicit pick (persistent default) on Stage Map open, then falls back to active microscope, then available presets. This lets the dropdown persist a cross-scope choice across sessions without needing manual re-selection.
- Removed auto-sync on image switch: `onOpenedImageChanged()` no longer resets the dropdown when the viewer switches to a derived entry with a different ancestor scanner. Users stay on their chosen preset until they manually pick a different one. This fixes a usability regression where the dropdown would jump away when switching between a PPM sub-acquisition and an Ocus40-macro parent on the same slide.
- Fresh-import entries (no `source_microscope` metadata) are no longer auto-stamped with the active microscope by Stage Map. That stamp now happens only via explicit workflows (Microscope Alignment, Existing Image workflow's source-mismatch dialog) so Stage Map can't silently corrupt acquisition-origin metadata.

**Orphaned flipped sibling locked the workflow in manual alignment**
- `(flipped X|Y|XY)` entries created by a previous (now-corrected) wrong-source run carry no `STAGE_BOUNDS_*` metadata of their own. Running the Existing Image workflow on such an entry fell through to `ManualAlignmentPath`, which reaches for the scanner's macro pixel size (e.g. 81 um/px) and assembles a transform off by ~125x against a 10x stitch -- the first refinement tile then targets a stage position hundreds of thousands of microns outside the limits. `ExistingImageWorkflowV2` now refuses at workflow start when the open entry is a flipped sibling whose base's `source_microscope` matches the active microscope, with a dialog pointing at the base entry. Genuine cross-scope siblings (e.g. PPM acquiring on an Ocus40 macro) are unaffected -- the base's source there is the external scanner, not the active microscope, so the orphan condition does not match.

**Stage Map "Show Acquisitions" reported 0 even for projects full of acquired stitches**
- The acquisition-overlay loader (`StageMapWindow.loadAndPaintAcquisitions`) called only `AffineTransformManager.loadSlideAlignment`, which scans `alignmentFiles/` -- the macro-frame directory. Auto-registered per-slide alignments for bounded acquisitions live in `alignmentFiles/derived/` (sub-frame), and many annotation-based acquisitions carry no JSON at all but DO have `STAGE_BOUNDS_*` stamped on the entry. The loader now uses a three-tier fallback: macro-frame JSON, then sub-frame JSON via `loadDerivedAlignment`, then `ImageMetadataManager.buildBoundingBoxPixelToStageTransform` against the entry's own metadata. Clicking the checkbox now also logs an `INFO` line on toggle and a summary line of how many of the project's entries yielded a usable alignment, so a 0-count outcome is visible in the log instead of silently buried in the status label.

**Forward/back propagation falsely reported "no alignment" for stitched bases**
- `ForwardPropagationWorkflow.buildGroups` decided propagation eligibility by calling only `loadSlideAlignment` (macro-frame). A base that is itself a bounded-acquired stitch lives in `alignmentFiles/derived/` (sub-frame, auto-registered) or carries `STAGE_BOUNDS_*` directly on the entry, so the eligibility check returned `false` even though Stage Map navigation worked fine on the same base. Now uses the same three-tier fallback as Show Acquisitions: macro JSON, derived JSON, then any variant entry's `STAGE_BOUNDS_*` metadata.
- The actual back-prop math also only loaded macro-frame JSONs (`PropagationManagerDialog`'s alignment lookup and `ForwardPropagationWorkflow.propagateBackFanOut`'s flip-frame recovery), so even when eligibility said "yes" the run aborted with *"no alignment file found for active scope"* for no-macro chains. Both sites now fall back to a new `AffineTransformManager.loadDerivedAlignmentWithFrame` that reads `alignmentFiles/derived/` for both the transform and the recorded `flipMacroX/Y`. Required for PPM/OWS3 imaging that starts on unstained tissue without an Ocus40 macro, where the very first acquisition is the navigation base.

**Single-tile refinement (missing tiles on flip-needing scopes)**
- Single-tile refinement could open the tile-select dialog with no tiles to pick when the workflow was started from the unflipped base image on a flip-needing scope (PPM). Root cause: the viewer's open entry could drift back to the base between routing and refinement (the slow white-background data-bounds classifier saturates the JavaFX thread, reordering the queued entry switch). Tiles were then created against the base entry's hierarchy and were invisible once the viewer settled on the flipped sibling. `performSingleTileRefinement` now re-asserts the flipped sibling as the open entry and re-reads annotations from it before creating tiles, so the annotations, tiles, and viewer all agree on one entry. Starting from the flipped sibling directly was already unaffected.

**Alignment transform loading (flip bake elimination)**
- Per-slide alignment transforms now load in their saved pixel frame without additional flip baking. This eliminates a PPM refinement bug where the stage would jump to the X/Y-mirror position of the selected tile instead of the intended tile. Root cause: the saved transform was already in the correct frame (the flipped sibling for flip-needing scopes, the unflipped base otherwise), but earlier code applied an additional flip-delta bake both at load and post-flip-switch, double-flipping the transform. `AlignmentHelper.checkForSlideAlignment` now loads transforms as-is, and `ImageFlipHelper.validateAndFlipIfNeeded` ensures the workflow operates on the entry the saved transform was built in.
- See `documentation/developer/COORDINATE_TRANSFORMS.md` § "Step 2" for the architectural shift.

**Re-stitch Tiles**
- Output folder now anchored to `<projectDir>/SlideImages` (matching the regular acquisition path) instead of landing under the imaging-mode folder or tile-selection folder.
- Output filenames now correctly follow the user-configured naming pattern (respecting FilenameIncludeModality, Objective, Annotation, Angle preferences) instead of duplicating angle names and misplacing indices.
- Compression and output-format selectors are now type-safe, and the compression choices are filtered by the selected format: OME-TIFF offers the full compression set, while ZARR-backed formats (OME_ZARR, OME_TIFF_VIA_ZARR) are restricted to LZW, ZLIB, Uncompressed, and Default. Codecs with no real ZARR algorithm can no longer be selected, so invalid combinations that would throw at the writer are now impossible.
- **OME_TIFF_VIA_ZARR format** now available: stitch to ZARR, then automatically queue a background conversion to OME-TIFF.


## [0.5.0] - 2026-05-07

### Added

**SIFT auto-alignment**
- Auto-Align (SIFT) button in the 3-point Microscope Alignment workflow per-tile confirm step (was previously only available in single-tile refinement). Reuses the same dialog and Settings, minus the "save / skip / new alignment" buttons.
- Configurable bit-depth normalization (`PERCENTILE` / `MIN_MAX` / `BIT_SHIFT`) and CLAHE preprocessing for cross-modality matching (16-bit monochrome camera vs 8-bit H&E WSI). Defaults `PERCENTILE` 2/98 + CLAHE on with clipLimit 2.0 fix the OWS3 + H&E "fails near correct tile" failure mode.
- Wire-protocol flags `--mono-norm`, `--pct-low`, `--pct-high`, `--clahe`, `--clahe-clip` on SIFTAL.

**Live Viewer**
- "Always Auto-Scale" checkbox replaces the forced per-tile rescale; drag the Min/Max sliders to opt out of auto-scale.
- Camera Control tab restores last-used Exposure + Intensity on open.
- Focus buttons recover when `restartStreaming` fails (no more deadlocked spinner).

**Autofocus Editor**
- Test Streaming Autofocus button with live curve plots; chart axes auto-fit.
- Surfaced `valid_modalities` and `min_magnification` constraints from the focus manifest.

**Propagation Manager**
- Ground-truth source ROI auto-stamped from parent tile detections; "Stamp source ROI from tiles" retrofit button for older sub-acquisitions.
- Cross-scope back-prop swaps in the source-scope alignment so the xy_offset is interpreted in its own scope's stage frame.
- "Remove existing objects of copied classes" checkbox: when set, deletes existing objects on each target whose class is in the selected set (and unclassified objects when "Unclassified" is also selected) before adding the propagated copies. Use case: refining annotations on the source image and re-propagating without overlapping the old shapes. FORWARD removes per-sub right before each `propagateForward`; BACK removes once per group across all base siblings before any sub fans out (otherwise multiple subs back-prop'ing into the same base would each delete and re-add, dropping earlier subs' contributions).
- Comprehensive diagnostics for back-prop; multi-group UI; "Save & reload viewer" handling.
- Visible warning when a source-scope config is missing instead of silently producing a wrong xy_offset.

**Acquisition Wizard**
- Calibration status pills (white balance, backgrounds, alignment) auto-refresh after sub-workflows complete via explicit completion notifications -- no more closing+reopening to clear yellow warnings after running WB / Background Collection / Microscope Alignment / Save Transform. Replaces the earlier 3-second polling Timeline (which worked but was a constant 6-file disk read for the wizard's entire lifetime). `AcquisitionWizardDialog.notifyCalibrationChanged()` is the public hook; `WhiteBalanceWorkflow`, `BackgroundCollectionWorkflow`, and `MicroscopeAlignmentWorkflow` call it on success. Refresh All button stays as the user-driven backup path.
- Unified `ObjectiveSelector` component that auto-detects from MicroManager pixel size (priority: MM pixel size match -> last-used pref -> first config entry).

**White Balance**
- R/B analog gain ceiling spinner exposed in the WB dialog and plumbed to the server.
- Objective/detector combos seeded from live MicroManager pixel size when opening WB / Background dialogs.

### Fixed

**Flip handling (Step B refactor + 2026-05-07 follow-ups)**
- Step B (2026-05-04): flip source-of-truth moved from per-entry `FLIP_X`/`FLIP_Y` metadata to `TransformPreset.flipMacroX/Y` (per `(source_scanner, target_microscope)` preset) and the per-slide alignment JSON. `AlignmentHelper.checkForSlideAlignment` bakes the alignment-frame flip into the loaded transform so all downstream callers consume unflipped-base pixel coords.
- 2026-05-04: per-slide JSONs auto-registered at BoundingBox stitch import + saved by ExistingImageWorkflow refinement now record `flipMacroX/Y` truthfully (the legacy 5-arg overload wrote `null,null` and was source of an XY-mirror bug on PPM existing-image acquisitions).
- 2026-05-07 (this release): `(flipped X|Y|XY)` companion entries are still created on demand by `ImageFlipHelper.validateAndFlipIfNeeded` for the visual-UX of operator alignment (the unflipped base and the flipped live camera view disagree by a mirror on PPM and cannot be aligned by eye). The 2026-05-04 stub-out of this method was reverted and replaced with a real preset-driven implementation.
- 2026-05-07: `ManualAlignmentPath` now records `flipMacroX/Y` on the per-slide JSON from the active preset (was via `FlipResolver(null,null,null)` which post-Step-B always returned `(false, false)`, mis-stamping the JSON as "no flip" when the alignment was actually done in the flipped sibling's frame).
- 2026-05-07: `StageControlPanel.handleGoToCentroid` (Move to Centroid button) now loads via `loadSlideAlignmentWithFrame` and bakes the flip on the unflipped base entry, mirroring `AlignmentHelper.checkForSlideAlignment`. The legacy raw-load + manual-compensation block was producing XY-mirror motion on PPM.
- 2026-05-07: tile detections in single-tile refinement now overlay the (correctly flipped) annotations on the flipped sibling instead of landing at the XY-mirror -- caused by `state.annotations` being captured against the unflipped base at config-dialog return and never re-fetched after the flip switch. `processSlideSpecificAlignment` now clears `state.annotations` after `validateAndFlipIfNeeded` so `ensureAnnotationsExist` re-reads from the now-current hierarchy.
- 2026-05-09: `ImageFlipHelper.switchOpenEntry` always defers the entry switch via `Platform.runLater` instead of running synchronously when already on the FX thread. The synchronous path threw `IllegalStateException: showAndWait is not allowed during animation or layout processing` followed by an NPE because the open call chain hit `QuPathGUI.checkSaveChanges` -> `Dialogs.showAndWait`, and the caller arrived inside a JavaFX animation pulse (synchronous continuation of `ProjectHelper.setupProject`'s post-creation Timeline). Hit any new user on their first existing-image acquisition with a flip-required preset (e.g. PPM).
- 2026-05-07: forward-prop now resolves the alignment-frame flip from the sub's parent entry's `FLIP_X`/`FLIP_Y` metadata (priority: sub-parent metadata > per-slide JSON > active preset). The previous "load flip from per-slide JSON" path produced 0 propagated objects on projects whose JSON was written by the pre-`45ca489` `ManualAlignmentPath` -- those JSONs claimed `hasFlipFrame=true` while actually storing `(false, false)`, so no pre-flip ran and source pixels mapped through `baseToStage` in the wrong frame. Sub-parent metadata is set at import and was unaffected by the JSON-save bug, so it's a more reliable signal. When the JSON disagrees with the parent metadata a warning is logged recommending re-alignment.

**Alignment-JSON lookup (2026-05-11 restructure, three layers)**
- Layer 1: workflow alignment lookup now resolves the parent macro entry's name from `base_image` metadata before reading the per-slide JSON, mirroring the long-standing `ForwardPropagationWorkflow` pattern. Previously, if the open entry was a stitched sub-image, the workflow loaded that sub-image's auto-registered alignment (scale = camera pixel size) and applied it to macro-frame annotation coords, silently shrinking every stage move by `camera_px / macro_px`. On the 2026-05-10 MH_Colon session this produced a 69% shrink: one tissue happened to fit inside its shrunk box, the other was clipped to half. Applied in `AlignmentHelper.checkForSlideAlignment`, `ExistingImageWorkflowV2.tryComposeCrossScopeAlignment`, and the refinement save in `ExistingImageWorkflowV2.saveRefinedAlignment`.
- Layer 2: alignment JSONs now carry a `pixelFrame` field (`"macro"` | `"sub"`). Sub-image auto-registrations in `StitchingHelper` stamp `"sub"`; every other save defaults to `"macro"`. `AlignmentHelper.checkForSlideAlignment` validates that loaded transforms are `"macro"` and refuses anything else with a hard-cancel dialog (same FX-safe pattern as the camera-ROI and pixel-size gates). Legacy JSONs without the field default to `"macro"` on load -- which is correct, since every shipped project's saved alignments predate the `"sub"` writer.
- Layer 3: sub-frame alignments now write into `alignmentFiles/derived/` instead of the flat `alignmentFiles/` directory, so macro lookups can no longer reach them by prefix-match. `AffineTransformManager.loadDerivedAlignment` reads `derived/` first with a flat-directory fallback for backward compatibility. The Live Viewer's sub-image Go-To-Centroid path opts in via this method.
- See `documentation/developer/WORKFLOW_DATA_FLOW.md` for the updated read/compare/write/gate map and a new `pixelFrame` row in the Sources of Truth table.

**Stage Map**
- Macro overlay flip now resolves from the active `(source, target)` preset, not per-entry metadata.
- Recovers when polling fails instead of hiding the overlay; logs the real failure cause.
- Recovers from stale `acquisitionActive` after cancellation.

**Connection stability**
- User-triggered Reconnect clears the server-unresponsive latch.
- Unified server-unresponsive recovery path across acquisition + heartbeat.
- Cancel runs off the JavaFX thread; CANC read timeout bumped so cancellation doesn't time out under server load.

**Class-filtered alignment tiling**
- `MicroscopeAlignmentWorkflow` (standalone) now prefers `PersistentPreferences.getSelectedAnnotationClasses()` for tiling, with Tissue / valid-class fallback chain. Removed the silent "all annotations regardless of class" fallback that produced noisy / overlapping tile grids.
- Existing Image Workflow's single-tile refinement prompt now reports the count and class names of annotations being tiled.

**Cropped camera ROI is now a hard cancel across all acquisition workflows**
- New `QPScopeChecks.validateCameraRoi(detector)` gate compares the live camera frame dimensions (queried from MicroManager via the existing `GETFRAME` socket call) against the configured sensor dimensions (`width_px` / `height_px` from `resources_LOCI.yml`). If they disagree by more than 5% on either axis, the workflow aborts with a dialog explaining how to fix the ROI in MicroManager.
- Motivating incident: a prior streaming-AF call cropped the JAI camera ROI to the centered 50% (1024x772 from a 2064x1544 sensor) and did not restore on its exit path. The cropped state persisted across QuPath/server restarts (MM remembers the last ROI), and every subsequent acquisition captured tiles at half the planned FoV -- producing stitched mosaics with ~50% empty space between every tile and breaking alignment (single-tile refinement landed at the wrong stage X/Y, with no flip configured to explain it).
- Wired through the same seven workflow gates as the pixel-size check: Existing Image, Bounded, Microscope Alignment, Rapid Scan, WB Comparison, White Balance, Background Collection.
- Root cause -- the streaming-AF restore path that misses some exit branches -- belongs in the Python `microscope_command_server` and is not addressed here. This is a defense-in-depth gate so the symptom is caught before any acquisition or alignment writes corrupted data. The server-side root cause was subsequently fixed on 2026-05-11 (microscope_command_server commit `7f40a47`): the streaming-AF code path now anchors absolutely on the full sensor (clear_roi -> crop -> clear_roi on every exit) so a leaked ROI is unreachable. This Java-side gate stays as belt-and-suspenders against future regressions or unrelated ROI manipulation paths.

**Objective pixel-size mismatch is a hard cancel across all acquisition workflows**
- Threshold lowered from 25% to 5%. Adjacent magnifications differ by 2x, so 5% is wide enough to absorb calibration drift but narrow enough to catch any user-induced mismatch (turret moved without updating the wizard, etc.).
- Removed the "Continue anyway?" confirm. On mismatch the workflow always cancels; user fixes MM or the wizard dropdown and restarts.
- Warning dialog enlarged (620-720 px wide, monospace body) and now lists the wizard objective + expected pixel size, MM-reported pixel size, the closest configured objective for MM's value (so the user immediately sees what MM probably has active), modality, detector, diff percent, plain-language consequences, and the fix.
- Gate now wired through Existing Image, Bounded Acquisition, Forward Propagation (offline-only -- gate is N/A there but documented), Microscope Alignment, Rapid Scan, WB Comparison, White Balance, and Background Collection. The previous code only checked Existing Image and Bounded; the others could silently produce gap-mosaics or write per-objective calibrations under the wrong magnification key.

**Other**
- Tile-handling preference (Delete / Zip / Keep) now actually runs after the Existing Image Workflow finishes stitching. The cleanup was previously only wired into `BoundedAcquisitionWorkflow`, so existing-image runs left their `tempTileDirectory` in place regardless of the preference. Both workflows now share `TileCleanupHelper.performCleanup`, and the Zip path only deletes originals if zipping succeeded. Background-correction tiles live in a separate config-specified folder and are unaffected.
- Tile cleanup ("Delete" and the post-zip step) now removes only `.tif` / `.tiff` files and prunes empty subdirectories. Previously it nuked the entire `tempTileDirectory` tree, taking `TileConfiguration.txt`, `TileConfiguration_QP.txt`, and `acquisition_command_*.txt` with it. Stitched OME-TIFFs in `SlideImages/` were unaffected, but per-region metadata needed for re-stitching or auditing was lost. Metadata files now survive in place; raw tiles still get removed.
- Live Viewer focus range dropdown options 6-40um (was truncated 1-20um).
- Sweep Focus rejects boundary peaks and requires 2 flanking samples for parabolic interpolation; suppresses early-stop on flat metric / peak-at-start.
- Test Standard/Adaptive Autofocus stops live view before running (no more "AF metric is whatever the camera last streamed" artifacts).
- Multichannel merged filenames follow the standard naming scheme.
- Camera tab Apply Profile rebuilds the panel and restores saved exposure/illumination prefs to hardware so UI matches.

### Documentation

- New `documentation/developer/WORKFLOW_DATA_FLOW.md` maps every acquisition workflow's read/compare/write/gate behavior. Required reading before changing any workflow's information flow.
- New `documentation/developer/COORDINATE_TRANSFORMS.md` is now authoritative for the flip pipeline.
- `documentation/developer/SOCKET_PROTOCOL.md` includes a full SIFTAL flag reference.
- `documentation/PREFERENCES.md` covers all SIFT bit-depth normalization knobs.
- `documentation/TROUBLESHOOTING.md` adds entries for SIFT failure modes (close-but-failing on PPM/H&E + stage-too-far-from-tile).
- `documentation/tools/microscope-alignment.md` and `documentation/tools/existing-image-acquisition.md` updated for SIFT-in-3-point alignment + class-filtered tiling.

## [0.3.0] - 2026-03-02

### Added

**Live Camera Viewer**
- Real-time camera feed window with streaming from MicroManager circular buffer
- 256-bin luminance histogram with per-channel saturation percentage (turns red when any channel exceeds 1%)
- Min/max contrast sliders with auto-scale, Fit mode, display scale selector
- RGB readouts for calibration diagnostics
- Double-click-to-center feature for stage navigation
- Integrated Stage Control panel (see below)

**Stage Control (integrated into Live Viewer)**
- Virtual joystick with quadratic response curve
- FOV-based step size dropdown for intuitive stage movement
- Two-tab layout: Movement tab and Saved Points tab
- Double-step arrows for coarse/fine movement
- Saved stage positions (named, persisted via JSON preferences)
- Sample movement mode with correct axis conventions
- Rate-limited joystick commands (prevents command flooding)

**White Balance System (JAI/Prism Cameras)**
- Full per-angle white balance calibration with iterative R/G/B exposure adjustment
- Four explicit WB modes: Off, Camera AWB, Simple, Per-angle
- Per-angle target intensity editing
- Advanced settings: max analog gain, gain threshold ratio, black level calibration
- Objective/detector selection with automatic calibration profile loading
- WB Comparison Test workflow for side-by-side WB mode comparison
- Gain values displayed in calibration results with Open Folder button

**Camera Control Dialog**
- Card-based layout with per-angle cards and color-coded exposure/gain rows
- Real-time gain validation with range tooltips
- Unified gain model (R/G/B shows analog gains, unified gain for all channels)
- Singleton dialog (brings to front if already open)

**Sunburst Calibration (PPM Reference Slide)**
- Hue-to-angle mapping workflow with radial spoke detection
- Advanced radial settings (inner/outer radius)
- Manual center selection UI for retry on detection failure
- Debug mask display in failure dialog
- All dialog settings persisted between sessions
- Redo callback and Go Back button in result dialogs

**JAI Noise Characterization**
- Quick/Full/Custom presets for noise measurement
- NoiseStatsPanel in Live Viewer (R/G/B Mean/StdDev/SNR grid)
- Persistent preferences, non-modal progress display

**CONFIG Command Handshake**
- Extension sends microscope configuration path to server on connection
- Server validates configuration before accepting commands
- User-friendly error handling for CONFIG failures

**Other New Features**
- Auxiliary socket for non-blocking Live Viewer and stage control operations during acquisition
- Objective pixel size mismatch warning before acquisition (queries MicroManager active pixel size)
- "No Manual Autofocus (Danger)" preference for unattended overnight acquisition
- CI workflow and Dependabot for automated build/dependency tracking
- Acquisition preview tile count now matches actual tiling logic

### Changed

**Menu Structure**
- Utilities menu reorganized by function (camera calibration, autofocus, PPM)
- "Starburst Calibration" renamed to "PPM Reference Slide..."
- "rectangles" renamed to "spokes" in all calibration dialogs
- Conditional "JAI Camera" submenu appears only when JAI camera detected
- Stage Control removed as separate menu item (integrated into Live Viewer)

**White Balance UI**
- Old boolean checkboxes ("White Balance" + "Per-Angle WB") replaced with single WB Mode ComboBox (Off / Camera AWB / Simple / Per-angle)
- WB mode applies on combo box selection directly (removed Apply button)
- Target Intensity moved from Shared Settings to Simple WB section
- Objective selection moved to Shared Settings (both Simple and PPM need it)

**Camera/Gain Model**
- Unified gain model replaces per-channel gain throughout
- Camera Control redesigned with card-based layout
- Gain mode radio buttons removed (gain is always unified; R/G/B shows analog gains)

**Dialog Behavior**
- Multiple dialogs made non-modal: calibration, birefringence progress, workflow progress, stage move
- Camera Control and Stage Control dialogs made singleton
- Exposure time fields removed from PPM angle selection dialog (exposures now auto-determined)
- Stage Map window doubled in size (840x760) with hollow crosshair circle and ScrollPane

**Configuration**
- Default calibration folder name changed to "ppm_reference_slide"
- Project logs moved to `<project>/logs/` subdirectory with config-based fallback
- Macro image crop bounds are now fully config-driven

### Fixed

**Stage Map / Macro Overlay**
- Fix macro overlay misplacement on flipped (inverted XY) image entries
- Fix macro overlay positioning, scale, aspect ratio, and flip handling (extensive series of fixes)
- Backward-compatible format marker for old vs new alignment files

**Autofocus**
- Fix focus Z position persistence across WB comparison modes
- Fix manual focus cancel deadlock (sends SKIPAF before CANCEL to unblock server)
- Fix manual focus skip preventing stitching in BoundedAcquisitionWorkflow

**Camera / White Balance**
- Fix camera_awb calibration lifecycle and gain handling
- Fix Camera Control showing stale WB values from cached config
- Fix per-angle WB not loading calibrated exposures
- Fix WB output path and singleton socket client usage
- Fix Continuous WB mode not applying in Camera Control

**Stitching**
- Fix birefringence stitching with directory isolation to prevent angle cross-matching
- Fix WB Comparison stitching (tile paths, Z position, monitoring, bounds nesting)

**Stage Movement**
- Fix stage movement to match MicroManager conventions
- Fix joystick direction in sample movement mode
- Fix joystick command flooding with rate limiting and latest-target-wins

**Annotations**
- CRITICAL: Fix annotation class filtering removing ALL annotations during alignment when no classes selected

**Live Viewer**
- Fix canvas expanding and frame poller race condition
- Fix camera mode changes failing when Live Viewer is streaming
- Fix Live Viewer image display sizing and display scale dropdown

**Other**
- Fix UI freeze when stage controls used during long operations
- Fix init-order NPE risk in ExistingImageAcquisitionController
- Fix birefringence optimization socket timeout during calibration
- Guard against setting BRIGHTFIELD_H_E on non-RGB images

### Removed
- "Apply & Go Snap Test" workflow and RAWSNAP command
- Standalone Stage Control menu item (integrated into Live Viewer)
- Legacy WB mode preferences: jaiWhiteBalanceMode, PPMPerAngleWBEnabled
- Gain mode radio buttons from Camera Control (gain always unified)

### Breaking Changes
- **CONFIG handshake required**: Extension now sends CONFIG command on connection; requires microscope-command-server v1.1.0+
- **WB mode parameter changed**: Old `--white-balance` and `--wb-per-angle` boolean flags replaced with `--wb-mode` enum (camera_awb|simple|per_angle|off)
- **Unified gain model**: GainsResult and CameraModeResult records changed; `setGains()` with count=3 now means [unified, analog_red, analog_blue]

## [0.2.0] - 2026-01-12

First release of a mostly working version, with code dedicated to working on one particular polychromatic polarization microscope. Expect significant changes as we migrate to more instruments.

## Guidelines for Release Notes

When creating a new release, add a section above with the version number and date:

```markdown
## [1.0.0] - 2026-01-XX

### Added
- Feature descriptions

### Changed
- Modification descriptions

### Fixed
- Bug fix descriptions

### Removed
- Removed feature descriptions
```

### Categories

- **Added** - New features
- **Changed** - Changes in existing functionality
- **Deprecated** - Soon-to-be removed features
- **Removed** - Now removed features
- **Fixed** - Bug fixes
- **Security** - Security improvements
