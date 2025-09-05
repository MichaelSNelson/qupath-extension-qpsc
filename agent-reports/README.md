# Agent Reporting System

This folder contains activity reports from specialized QPSC agents, organized for efficient tracking and main chat review.

## Folder Structure

```
agent-reports/
├── testing-validation/        # Test creation, validation reports
├── documentation-examples/    # Documentation updates, examples
├── gui/                      # UI improvements, component updates  
├── acquisition-logic/        # Acquisition optimization, algorithms
├── workflow-optimization/    # End-to-end workflow improvements
├── templates/               # Report templates for consistency
└── summaries/              # Daily/weekly rollup summaries
```

## Report Types

### 1. Standard Activity Reports
**Location:** `agent-reports/[agent-name]/YYYY-MM-DD_HHMM_[activity-type].md`
**Template:** `templates/standard-report-template.md`

**Activity Types:**
- `code-changes` - Source code modifications
- `test-creation` - New test development
- `documentation` - Documentation updates
- `optimization` - Performance improvements
- `bug-fixes` - Issue resolution
- `feature-additions` - New functionality

### 2. Daily Summary Reports  
**Location:** `summaries/YYYY-MM-DD_daily-summary.md`
**Template:** `templates/daily-summary-template.md`
**Purpose:** Consolidated view of all agent activities for quick main chat review

### 3. Integration Reports
**Location:** `summaries/YYYY-MM-DD_HHMM_integration-[id].md`  
**Template:** `templates/integration-report-template.md`
**Purpose:** Track when multiple agents' work is merged and validated

## Report Naming Convention

```
YYYY-MM-DD_HHMM_[agent-type]_[activity-type].md

Examples:
2025-01-15_1430_gui_component-updates.md
2025-01-15_0900_testing_unit-test-expansion.md
2025-01-15_1600_acquisition_socket-optimization.md
```

## Quick Scanning Format

Each report uses consistent structure for rapid review:

```
AGENT | DATE | TIME | IMPACT | ACTIVITY | FILES CHANGED | STATUS
------|------|------|---------|----------|---------------|--------
GUI   | 01-15| 14:30| HIGH   | UI Update| 3 files      | ✓ DONE
TEST  | 01-15| 09:00| MEDIUM | Unit Tests| 2 files     | ⏳ REVIEW
```

## Impact Levels

- **HIGH**: Architecture changes, API modifications, critical bug fixes
- **MEDIUM**: Feature additions, significant optimizations, documentation updates
- **LOW**: Minor improvements, code cleanup, comment additions

## Review Workflow

1. **Agent creates report** using standard template
2. **Daily summary** aggregates all activities  
3. **Main chat reviews** high-impact items first
4. **Integration report** documents merged changes
5. **Archive** completed reports for historical tracking

## Main Chat Priority Queue

Reports needing immediate main chat attention are flagged with:
- 🔴 **URGENT** - Breaking changes, critical bugs
- 🟡 **REVIEW** - High-impact changes ready for integration
- 🟢 **INFO** - Completed work, no action needed

## Search and Reference

Reports are searchable by:
- **Agent name** - Find all activities from specific agent
- **Date range** - Track progress over time
- **File paths** - See all changes to specific files
- **Activity type** - Group similar work across agents
- **Impact level** - Focus on high-priority items

## Templates Usage

Copy templates from `templates/` folder:
```bash
cp agent-reports/templates/standard-report-template.md \
   agent-reports/[agent-name]/$(date +%Y-%m-%d_%H%M)_[activity].md
```

## Integration with Development Workflow

- Reports link to PRs, issues, and commits
- Test status tracked for each change
- Dependencies between agent work documented
- Rollback procedures included for critical changes

---
*System established: 2025-01-15 | Last updated: [AUTO] | Next review: Daily*