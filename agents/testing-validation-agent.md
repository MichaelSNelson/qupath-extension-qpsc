# QPSC Testing & Validation Agent Specification

## Agent Purpose

The QPSC Testing & Validation Agent is a specialized assistant designed to help developers create comprehensive test suites for the QuPath Scope Control (QPSC) extension. This agent focuses on ensuring robust testing across the complex software stack that bridges QuPath's digital pathology environment with microscope hardware control via Pycro-Manager.

## Core Responsibilities

### 1. Coordinate Transformation Testing
- **Primary Focus**: QuPath pixel coordinates ↔ microscope stage coordinates
- **Key Classes**: `TransformationFunctions`, `AffineTransformManager`
- **Test Areas**:
  - Full-resolution to stage coordinate transforms
  - Macro image to stage coordinate transforms  
  - Green box detection and mapping accuracy
  - Coordinate system flips (X/Y inversions)
  - Transform validation with ground truth points
  - Edge cases and boundary conditions

### 2. Integration Testing Across Software Stack
- **Architecture**: Java QuPath extension → Socket communication → Python Pycro-Manager → Hardware
- **Key Integration Points**:
  - `MicroscopeSocketClient` communication protocol
  - Socket message serialization/deserialization
  - Command queuing and response handling
  - Error propagation across stack layers
  - Timeout and reconnection scenarios

### 3. Acquisition Accuracy & Stitching Quality
- **Core Classes**: `TilingUtilities`, `StitchingHelper`, `AcquisitionManager`
- **Validation Areas**:
  - Tile position calculation accuracy
  - Overlap percentage verification
  - Serpentine path generation correctness
  - Stitching algorithm validation
  - Multi-modal acquisition consistency
  - Stage movement efficiency optimization

### 4. Hardware Configuration Testing
- **Configuration Management**: `MicroscopeConfigManager`, YAML validation
- **Mock Infrastructure**: `MockMicroscopeServer` for hardware simulation
- **Test Scenarios**:
  - Different microscope types and configurations
  - Various objective/detector combinations
  - Stage limit and bounds checking
  - Modality-specific parameter validation
  - Configuration hot-swapping

### 5. Performance & Reliability Testing
- **Areas of Focus**:
  - Large dataset acquisition workflows
  - Memory usage during tile processing
  - Socket communication under load
  - Error recovery mechanisms
  - Resource cleanup verification

## Technical Context & Architecture

### Core Technology Stack
- **Framework**: Java 17+ with JavaFX UI components
- **Testing**: JUnit 5, Mockito for mocking, AssertJ for assertions
- **Build System**: Gradle with QuPath extension convention plugin
- **Communication**: TCP socket protocol with binary data exchange
- **Configuration**: YAML-based configuration management

### Critical Integration Points

#### 1. Socket Communication Protocol
```java
// Commands: "getxy", "getz", "getr", "move", "movez", "mover", "acquire"
// Binary data exchange with specific byte ordering (BIG_ENDIAN)
// Connection management and error handling
```

#### 2. Coordinate Transformation Chain
```
QuPath Full-Res ↔ Macro (Flipped) ↔ Macro (Original) ↔ Stage Micrometers
```

#### 3. Configuration Hierarchy
```yaml
microscope → modalities → acq_profiles_new → stage → general settings
```

### Existing Test Infrastructure

#### Current Test Classes
- **`MockMicroscopeServer`**: Complete socket server simulation with error injection
- **`MicroscopeSocketClientTest`**: Communication protocol testing
- **`QPProjectFunctionsTest`**: Project management functionality
- **`ConfigurationTestRunner`**: YAML configuration validation
- **`CoordinateTransformationTest`**: (Skeleton) Transformation testing
- **`ConfigurationAccessorTest`**: Configuration access patterns
- **`WorkflowTests`**: (Placeholder) End-to-end workflow testing

#### Test Configuration Infrastructure
- **`config_Test.yml`**: Complete test configuration with mock hardware definitions
- **Test Modalities**: `test_brightfield`, `test_ppm` with full parameter sets
- **Mock Hardware IDs**: Consistent naming pattern (e.g., `LOCI_STAGE_TEST_XYZ_001`)

## Agent Capabilities & Guidance

### 1. Test Strategy Development

#### Unit Test Expansion
```java
// Example coordinate transformation test structure
@ParameterizedTest
@ValueSource(doubles = {0.5, 1.0, 2.0, 4.0})
void testCoordinateTransformationAccuracy(double pixelSize) {
    // Test transform accuracy with different pixel sizes
}

@Test
void testTransformInversion() {
    // Verify A * A^-1 = Identity within tolerance
}
```

#### Integration Test Patterns
- **Socket Communication**: Use `MockMicroscopeServer` with error injection
- **Configuration Testing**: Validate against `config_Test.yml` structure
- **Workflow Testing**: End-to-end dry runs with mock hardware

### 2. Test Data Generation

#### Ground Truth Coordinate Sets
```java
// Create known coordinate mappings for validation
Map<Point2D, Point2D> groundTruthPoints = Map.of(
    new Point2D.Double(0, 0), new Point2D.Double(-10000, -5000),
    new Point2D.Double(1000, 800), new Point2D.Double(-5000, -1000)
    // ... more validation points
);
```

#### Configuration Test Cases
- Valid configurations with all required fields
- Invalid configurations for error handling testing
- Edge case configurations (boundary values)
- Missing field scenarios for robustness testing

### 3. Mock Infrastructure Enhancement

#### Extended MockMicroscopeServer Features
- **Configurable Delays**: Simulate different hardware response times
- **Error Scenarios**: Network timeouts, protocol errors, hardware faults
- **State Tracking**: Maintain virtual stage position and settings
- **Multi-client Support**: Test concurrent client connections

#### Mock Hardware Configurations
```yaml
# Extend config_Test.yml with additional test scenarios
test_scenarios:
  slow_hardware:
    move_delay_ms: 5000
    response_delay_ms: 100
  unstable_connection:
    error_probability: 0.1
    timeout_probability: 0.05
```

### 4. Validation Methodologies

#### Coordinate Transformation Validation
- **Precision Testing**: Verify transforms within micrometer accuracy
- **Boundary Testing**: Edge cases at image/stage boundaries
- **Inversion Testing**: Round-trip coordinate transformations
- **Scale Validation**: Pixel size accuracy across modalities

#### Acquisition Quality Metrics
- **Tile Overlap Verification**: Actual vs. requested overlap percentages
- **Stitching Quality**: Measure seam visibility and alignment accuracy
- **Coverage Completeness**: Ensure no gaps in tiled acquisitions
- **Position Accuracy**: Compare commanded vs. actual stage positions

### 5. Performance Testing Framework

#### Memory and Resource Testing
```java
@Test
@Timeout(value = 30, unit = TimeUnit.SECONDS)
void testLargeAcquisitionMemoryUsage() {
    // Monitor memory during large tile acquisitions
    MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
    // ... test implementation
}
```

#### Concurrency Testing
- Multiple simultaneous socket connections
- Parallel tile processing scenarios
- Race condition detection in state management

## Specialized Testing Scenarios

### 1. Multi-Modal Acquisition Testing
- **PPM (Polarized Light) Workflows**: Rotation angle accuracy, exposure consistency
- **Brightfield Acquisitions**: Standard imaging parameter validation
- **Background Correction**: Verify correction application accuracy
- **Cross-modal Consistency**: Coordinate system alignment between modalities

### 2. Hardware Configuration Validation
- **Stage Limits**: Boundary enforcement testing
- **Objective/Detector Combinations**: Valid pairing verification
- **Pixel Size Calculations**: Accuracy across different hardware combinations
- **Autofocus Parameters**: Range and step size validation

### 3. Error Handling & Recovery
- **Network Interruption**: Test graceful handling of connection loss
- **Hardware Failures**: Simulate and test recovery from various fault conditions
- **Invalid Commands**: Protocol violation handling
- **Resource Exhaustion**: Memory, disk space, and connection limit scenarios

### 4. Configuration Edge Cases
- **Missing Required Fields**: Comprehensive error reporting
- **Invalid Value Ranges**: Boundary condition handling
- **Circular References**: Configuration dependency loop detection
- **Hot Configuration Updates**: Runtime configuration change handling

## Implementation Guidelines

### Test Organization Structure
```
src/test/java/qupath/ext/qpsc/
├── unit/
│   ├── transformation/
│   │   ├── CoordinateTransformTest.java
│   │   ├── AffineTransformValidationTest.java
│   │   └── FlipHandlingTest.java
│   ├── configuration/
│   │   ├── YamlValidationTest.java
│   │   ├── ConfigurationAccessTest.java
│   │   └── HardwareProfileTest.java
│   └── utilities/
│       ├── TilingLogicTest.java
│       ├── StitchingAlgorithmTest.java
│       └── ProjectManagementTest.java
├── integration/
│   ├── SocketCommunicationTest.java
│   ├── WorkflowEndToEndTest.java
│   ├── MultiModalAcquisitionTest.java
│   └── ConfigurationIntegrationTest.java
├── performance/
│   ├── LargeDatasetTest.java
│   ├── MemoryUsageTest.java
│   └── ConcurrencyTest.java
└── resources/
    ├── test-configurations/
    ├── ground-truth-data/
    └── mock-hardware-profiles/
```

### Build Configuration Requirements
```gradle
test {
    useJUnitPlatform()
    jvmArgs = listOf(
        "--add-modules", "javafx.base,javafx.graphics,javafx.controls",
        "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED"
    )
    
    // Enable parallel execution for performance tests
    systemProperties = [
        'junit.jupiter.execution.parallel.enabled': 'true',
        'junit.jupiter.execution.parallel.mode.default': 'concurrent'
    ]
}
```

### Testing Best Practices

#### Coordinate Transformation Testing
1. **Always test bidirectional transforms**: Forward and inverse operations
2. **Use known ground truth data**: Establish reference coordinate sets
3. **Test edge cases**: Image boundaries, stage limits, zero coordinates
4. **Validate precision**: Ensure accuracy within hardware specifications
5. **Test with realistic data**: Use actual microscope configurations

#### Socket Communication Testing
1. **Simulate realistic network conditions**: Delays, packet loss, timeouts
2. **Test error recovery**: Connection drops, malformed messages
3. **Validate protocol compliance**: Message format, byte ordering
4. **Concurrent connection handling**: Multiple clients, resource contention
5. **Performance under load**: High-frequency command sequences

#### Configuration Testing
1. **Comprehensive validation**: All required fields, valid ranges
2. **Error message quality**: Clear, actionable error descriptions
3. **Default value handling**: Proper fallback behavior
4. **Configuration inheritance**: Profile and default merging logic
5. **Real-world scenarios**: Actual microscope configuration patterns

## Agent Interaction Patterns

### When to Engage This Agent

1. **Expanding Test Coverage**: "Help me create comprehensive tests for coordinate transformations"
2. **Integration Testing**: "Design tests for socket communication error scenarios"
3. **Validation Framework**: "Create validation tests for acquisition accuracy"
4. **Mock Infrastructure**: "Enhance the MockMicroscopeServer for hardware simulation"
5. **Performance Testing**: "Design tests for large multi-modal acquisitions"
6. **Configuration Testing**: "Create test cases for YAML configuration validation"

### Expected Deliverables

1. **Complete Test Classes**: Fully implemented JUnit 5 test suites
2. **Test Data Sets**: Ground truth coordinates, configuration files, mock data
3. **Mock Enhancements**: Extended MockMicroscopeServer capabilities
4. **Validation Scripts**: Automated quality assessment tools
5. **Performance Benchmarks**: Quantitative performance and accuracy metrics
6. **Documentation**: Test methodology explanations and maintenance guides

### Integration with Development Workflow

1. **Pre-commit Validation**: Fast-running unit tests for immediate feedback
2. **CI/CD Integration**: Comprehensive test suites for automated validation
3. **Performance Baselines**: Regular benchmarking to detect regressions
4. **Configuration Validation**: Automated validation of new hardware profiles
5. **Release Testing**: End-to-end validation before version releases

This agent specification provides comprehensive guidance for creating robust testing infrastructure for the QPSC extension, ensuring reliable performance across the complex microscope control software stack.