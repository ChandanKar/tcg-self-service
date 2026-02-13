package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.Ec2InstanceActionResponse;
import com.tcgdigital.vmcontrol.dto.Ec2InstanceInfo;
import com.tcgdigital.vmcontrol.dto.Ec2InstanceStatus;
import com.tcgdigital.vmcontrol.service.Ec2Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EC2 Controller Tests")
class Ec2ControllerTest {

    private static final String REGION = "ap-south-1";
    private static final String API_ENDPOINT = "/api/ec2/instances";
    private static final String INSTANCE_ID = "i-0123456789abcdef0";

    @Mock
    private Ec2Service ec2Service;

    @InjectMocks
    private Ec2Controller ec2Controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ec2Controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Should return list of EC2 instances for ap-south-1 region")
    void listInstances_WithValidRegion_ReturnsInstanceList() throws Exception {
        // Arrange
        List<Ec2InstanceInfo> mockInstances = createMockEc2Instances();
        when(ec2Service.listInstances(eq(REGION))).thenReturn(mockInstances);

        // Act & Assert
        mockMvc.perform(get(API_ENDPOINT)
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].instanceId", is("i-0123456789abcdef0")))
                .andExpect(jsonPath("$[0].instanceType", is("t2.micro")))
                .andExpect(jsonPath("$[0].state", is("running")))
                .andExpect(jsonPath("$[0].region", is(REGION)))
                .andExpect(jsonPath("$[0].privateIpAddress", is("10.0.1.100")))
                .andExpect(jsonPath("$[0].publicIpAddress", is("13.232.100.50")))
                .andExpect(jsonPath("$[0].availabilityZone", is("ap-south-1a")))
                .andExpect(jsonPath("$[1].instanceId", is("i-0987654321fedcba0")))
                .andExpect(jsonPath("$[1].instanceType", is("t3.medium")))
                .andExpect(jsonPath("$[1].state", is("stopped")));

        verify(ec2Service, times(1)).listInstances(eq(REGION));
    }

    @Test
    @DisplayName("Should return empty list when no instances exist in ap-south-1 region")
    void listInstances_NoInstances_ReturnsEmptyList() throws Exception {
        // Arrange
        when(ec2Service.listInstances(eq(REGION))).thenReturn(Collections.emptyList());

        // Act & Assert
        mockMvc.perform(get(API_ENDPOINT)
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)));

        verify(ec2Service, times(1)).listInstances(eq(REGION));
    }

    @Test
    @DisplayName("Should return single instance details correctly")
    void listInstances_SingleInstance_ReturnsCorrectDetails() throws Exception {
        // Arrange
        Ec2InstanceInfo singleInstance = createSingleMockInstance();
        when(ec2Service.listInstances(eq(REGION))).thenReturn(List.of(singleInstance));

        // Act & Assert
        mockMvc.perform(get(API_ENDPOINT)
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].instanceId", is("i-test123456")))
                .andExpect(jsonPath("$[0].vpcId", is("vpc-ap-south-1-test")))
                .andExpect(jsonPath("$[0].subnetId", is("subnet-ap-south-1a-test")))
                .andExpect(jsonPath("$[0].imageId", is("ami-0123456789abcdef0")))
                .andExpect(jsonPath("$[0].keyName", is("my-mumbai-keypair")))
                .andExpect(jsonPath("$[0].platform", is("linux")))
                .andExpect(jsonPath("$[0].architecture", is("x86_64")))
                .andExpect(jsonPath("$[0].tags.Name", is("TestServer")))
                .andExpect(jsonPath("$[0].tags.Environment", is("Development")))
                .andExpect(jsonPath("$[0].securityGroupIds", hasSize(2)))
                .andExpect(jsonPath("$[0].ebsOptimized", is(true)))
                .andExpect(jsonPath("$[0].coreCount", is(2)))
                .andExpect(jsonPath("$[0].threadsPerCore", is(2)));

        verify(ec2Service).listInstances(REGION);
    }

    @Test
    @DisplayName("Should return 400 when region parameter is missing")
    void listInstances_MissingRegion_ReturnsBadRequest() throws Exception {
        // Act & Assert
        mockMvc.perform(get(API_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(ec2Service, never()).listInstances(anyString());
    }

    @Test
    @DisplayName("Should handle service exception gracefully")
    void listInstances_ServiceThrowsException_ReturnsServerError() throws Exception {
        // Arrange
        when(ec2Service.listInstances(eq(REGION)))
                .thenThrow(new RuntimeException("AWS connection failed"));

        // Act & Assert
        mockMvc.perform(get(API_ENDPOINT)
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError());

        verify(ec2Service, times(1)).listInstances(eq(REGION));
    }

    @Test
    @DisplayName("Should verify service is called with correct region parameter")
    void listInstances_VerifyServiceCalledWithCorrectRegion() throws Exception {
        // Arrange
        when(ec2Service.listInstances(anyString())).thenReturn(Collections.emptyList());

        // Act
        mockMvc.perform(get(API_ENDPOINT)
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Assert - verify exact region parameter
        verify(ec2Service).listInstances(REGION);
        verifyNoMoreInteractions(ec2Service);
    }

    // Helper methods to create mock data

    private List<Ec2InstanceInfo> createMockEc2Instances() {
        Ec2InstanceInfo instance1 = new Ec2InstanceInfo(
                "i-0123456789abcdef0",
                "t2.micro",
                "running",
                "10.0.1.100",
                "13.232.100.50",
                "ip-10-0-1-100.ap-south-1.compute.internal",
                "ec2-13-232-100-50.ap-south-1.compute.amazonaws.com",
                "vpc-mumbai-123",
                "subnet-mumbai-1a",
                "ap-south-1a",
                "ami-0123456789abcdef0",
                "my-key-pair",
                Instant.parse("2024-01-15T10:30:00Z"),
                "linux",
                "x86_64",
                "ebs",
                "/dev/xvda",
                "hvm",
                "xen",
                "arn:aws:iam::123456789012:instance-profile/MyRole",
                List.of("sg-0123456789abcdef0"),
                List.of("my-security-group"),
                Map.of("Name", "WebServer", "Environment", "Production"),
                false,
                "disabled",
                "default",
                1,
                1,
                REGION
        );

        Ec2InstanceInfo instance2 = new Ec2InstanceInfo(
                "i-0987654321fedcba0",
                "t3.medium",
                "stopped",
                "10.0.2.50",
                null,
                "ip-10-0-2-50.ap-south-1.compute.internal",
                null,
                "vpc-mumbai-456",
                "subnet-mumbai-1b",
                "ap-south-1b",
                "ami-fedcba9876543210",
                "another-key",
                Instant.parse("2024-02-20T14:00:00Z"),
                "linux",
                "x86_64",
                "ebs",
                "/dev/sda1",
                "hvm",
                "nitro",
                null,
                List.of("sg-fedcba9876543210"),
                List.of("database-sg"),
                Map.of("Name", "DatabaseServer", "Environment", "Staging"),
                true,
                "enabled",
                "default",
                2,
                2,
                REGION
        );

        return Arrays.asList(instance1, instance2);
    }

    private Ec2InstanceInfo createSingleMockInstance() {
        return new Ec2InstanceInfo(
                "i-test123456",
                "t3.large",
                "running",
                "10.0.10.25",
                "13.232.200.100",
                "ip-10-0-10-25.ap-south-1.compute.internal",
                "ec2-13-232-200-100.ap-south-1.compute.amazonaws.com",
                "vpc-ap-south-1-test",
                "subnet-ap-south-1a-test",
                "ap-south-1a",
                "ami-0123456789abcdef0",
                "my-mumbai-keypair",
                Instant.parse("2024-03-01T08:00:00Z"),
                "linux",
                "x86_64",
                "ebs",
                "/dev/xvda",
                "hvm",
                "nitro",
                "arn:aws:iam::123456789012:instance-profile/TestRole",
                List.of("sg-test1", "sg-test2"),
                List.of("test-sg-1", "test-sg-2"),
                Map.of("Name", "TestServer", "Environment", "Development", "Team", "DevOps"),
                true,
                "enabled",
                "default",
                2,
                2,
                REGION
        );
    }

    // ==================== Start Instance Tests ====================

    @Test
    @DisplayName("Should start instance successfully in ap-south-1 region")
    void startInstance_Success_ReturnsActionResponse() throws Exception {
        // Arrange
        Ec2InstanceActionResponse mockResponse = new Ec2InstanceActionResponse(
                INSTANCE_ID,
                "start",
                "stopped",
                "running",
                true,
                "Instance started successfully and status checks passed",
                REGION
        );
        when(ec2Service.startInstance(eq(INSTANCE_ID), eq(REGION))).thenReturn(mockResponse);

        // Act & Assert
        mockMvc.perform(post(API_ENDPOINT + "/" + INSTANCE_ID + "/start")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.instanceId", is(INSTANCE_ID)))
                .andExpect(jsonPath("$.action", is("start")))
                .andExpect(jsonPath("$.previousState", is("stopped")))
                .andExpect(jsonPath("$.currentState", is("running")))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.message", containsString("status checks passed")))
                .andExpect(jsonPath("$.region", is(REGION)));

        verify(ec2Service, times(1)).startInstance(eq(INSTANCE_ID), eq(REGION));
    }

    @Test
    @DisplayName("Should return success when instance is already running with passed status checks")
    void startInstance_AlreadyRunning_ReturnsSuccessResponse() throws Exception {
        // Arrange
        Ec2InstanceActionResponse mockResponse = new Ec2InstanceActionResponse(
                INSTANCE_ID,
                "start",
                "running",
                "running",
                true,
                "Instance is already running and status checks are passed",
                REGION
        );
        when(ec2Service.startInstance(eq(INSTANCE_ID), eq(REGION))).thenReturn(mockResponse);

        // Act & Assert
        mockMvc.perform(post(API_ENDPOINT + "/" + INSTANCE_ID + "/start")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.message", containsString("already running")));

        verify(ec2Service).startInstance(INSTANCE_ID, REGION);
    }

    @Test
    @DisplayName("Should return failure when instance is running but status checks not passed")
    void startInstance_RunningButStatusChecksFailed_ReturnsFailureResponse() throws Exception {
        // Arrange
        Ec2InstanceActionResponse mockResponse = new Ec2InstanceActionResponse(
                INSTANCE_ID,
                "start",
                "running",
                "running",
                false,
                "Instance is running but status checks are not passed. System: initializing, Instance: initializing",
                REGION
        );
        when(ec2Service.startInstance(eq(INSTANCE_ID), eq(REGION))).thenReturn(mockResponse);

        // Act & Assert
        mockMvc.perform(post(API_ENDPOINT + "/" + INSTANCE_ID + "/start")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.message", containsString("status checks are not passed")));

        verify(ec2Service).startInstance(INSTANCE_ID, REGION);
    }

    @Test
    @DisplayName("Should return failure when start times out waiting for running state")
    void startInstance_Timeout_ReturnsFailureResponse() throws Exception {
        // Arrange
        Ec2InstanceActionResponse mockResponse = new Ec2InstanceActionResponse(
                INSTANCE_ID,
                "start",
                "stopped",
                "pending",
                false,
                "Instance start initiated but timed out waiting for running state",
                REGION
        );
        when(ec2Service.startInstance(eq(INSTANCE_ID), eq(REGION))).thenReturn(mockResponse);

        // Act & Assert
        mockMvc.perform(post(API_ENDPOINT + "/" + INSTANCE_ID + "/start")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.currentState", is("pending")))
                .andExpect(jsonPath("$.message", containsString("timed out")));

        verify(ec2Service).startInstance(INSTANCE_ID, REGION);
    }

    @Test
    @DisplayName("Should return 400 when region is missing for start instance")
    void startInstance_MissingRegion_ReturnsBadRequest() throws Exception {
        // Act & Assert
        mockMvc.perform(post(API_ENDPOINT + "/" + INSTANCE_ID + "/start")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(ec2Service, never()).startInstance(anyString(), anyString());
    }

    // ==================== Stop Instance Tests ====================

    @Test
    @DisplayName("Should stop instance successfully in ap-south-1 region")
    void stopInstance_Success_ReturnsActionResponse() throws Exception {
        // Arrange
        Ec2InstanceActionResponse mockResponse = new Ec2InstanceActionResponse(
                INSTANCE_ID,
                "stop",
                "running",
                "stopped",
                true,
                "Instance stopped successfully",
                REGION
        );
        when(ec2Service.stopInstance(eq(INSTANCE_ID), eq(REGION))).thenReturn(mockResponse);

        // Act & Assert
        mockMvc.perform(post(API_ENDPOINT + "/" + INSTANCE_ID + "/stop")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.instanceId", is(INSTANCE_ID)))
                .andExpect(jsonPath("$.action", is("stop")))
                .andExpect(jsonPath("$.previousState", is("running")))
                .andExpect(jsonPath("$.currentState", is("stopped")))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.message", is("Instance stopped successfully")))
                .andExpect(jsonPath("$.region", is(REGION)));

        verify(ec2Service, times(1)).stopInstance(eq(INSTANCE_ID), eq(REGION));
    }

    @Test
    @DisplayName("Should return success when instance is already stopped")
    void stopInstance_AlreadyStopped_ReturnsSuccessResponse() throws Exception {
        // Arrange
        Ec2InstanceActionResponse mockResponse = new Ec2InstanceActionResponse(
                INSTANCE_ID,
                "stop",
                "stopped",
                "stopped",
                true,
                "Instance is already stopped",
                REGION
        );
        when(ec2Service.stopInstance(eq(INSTANCE_ID), eq(REGION))).thenReturn(mockResponse);

        // Act & Assert
        mockMvc.perform(post(API_ENDPOINT + "/" + INSTANCE_ID + "/stop")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.message", containsString("already stopped")));

        verify(ec2Service).stopInstance(INSTANCE_ID, REGION);
    }

    @Test
    @DisplayName("Should return failure when stop times out")
    void stopInstance_Timeout_ReturnsFailureResponse() throws Exception {
        // Arrange
        Ec2InstanceActionResponse mockResponse = new Ec2InstanceActionResponse(
                INSTANCE_ID,
                "stop",
                "running",
                "stopping",
                false,
                "Instance stop initiated but timed out waiting for stopped state",
                REGION
        );
        when(ec2Service.stopInstance(eq(INSTANCE_ID), eq(REGION))).thenReturn(mockResponse);

        // Act & Assert
        mockMvc.perform(post(API_ENDPOINT + "/" + INSTANCE_ID + "/stop")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.currentState", is("stopping")))
                .andExpect(jsonPath("$.message", containsString("timed out")));

        verify(ec2Service).stopInstance(INSTANCE_ID, REGION);
    }

    @Test
    @DisplayName("Should return 400 when region is missing for stop instance")
    void stopInstance_MissingRegion_ReturnsBadRequest() throws Exception {
        // Act & Assert
        mockMvc.perform(post(API_ENDPOINT + "/" + INSTANCE_ID + "/stop")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(ec2Service, never()).stopInstance(anyString(), anyString());
    }

    // ==================== Get Instance Status Tests ====================

    @Test
    @DisplayName("Should return instance status for running instance in ap-south-1")
    void getInstanceStatus_RunningInstance_ReturnsStatusResponse() throws Exception {
        // Arrange
        Ec2InstanceStatus mockStatus = new Ec2InstanceStatus(
                INSTANCE_ID,
                "running",
                "ok",
                "ok",
                "ap-south-1a",
                REGION
        );
        when(ec2Service.getInstanceStatus(eq(INSTANCE_ID), eq(REGION))).thenReturn(mockStatus);

        // Act & Assert
        mockMvc.perform(get(API_ENDPOINT + "/" + INSTANCE_ID + "/status")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.instanceId", is(INSTANCE_ID)))
                .andExpect(jsonPath("$.instanceState", is("running")))
                .andExpect(jsonPath("$.systemStatus", is("ok")))
                .andExpect(jsonPath("$.instanceStatus", is("ok")))
                .andExpect(jsonPath("$.availabilityZone", is("ap-south-1a")))
                .andExpect(jsonPath("$.region", is(REGION)));

        verify(ec2Service, times(1)).getInstanceStatus(eq(INSTANCE_ID), eq(REGION));
    }

    @Test
    @DisplayName("Should return instance status for stopped instance")
    void getInstanceStatus_StoppedInstance_ReturnsStatusResponse() throws Exception {
        // Arrange
        Ec2InstanceStatus mockStatus = new Ec2InstanceStatus(
                INSTANCE_ID,
                "stopped",
                "not-applicable",
                "not-applicable",
                "ap-south-1b",
                REGION
        );
        when(ec2Service.getInstanceStatus(eq(INSTANCE_ID), eq(REGION))).thenReturn(mockStatus);

        // Act & Assert
        mockMvc.perform(get(API_ENDPOINT + "/" + INSTANCE_ID + "/status")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceState", is("stopped")))
                .andExpect(jsonPath("$.systemStatus", is("not-applicable")))
                .andExpect(jsonPath("$.instanceStatus", is("not-applicable")));

        verify(ec2Service).getInstanceStatus(INSTANCE_ID, REGION);
    }

    @Test
    @DisplayName("Should return instance status with initializing status checks")
    void getInstanceStatus_InitializingChecks_ReturnsStatusResponse() throws Exception {
        // Arrange
        Ec2InstanceStatus mockStatus = new Ec2InstanceStatus(
                INSTANCE_ID,
                "running",
                "initializing",
                "initializing",
                "ap-south-1a",
                REGION
        );
        when(ec2Service.getInstanceStatus(eq(INSTANCE_ID), eq(REGION))).thenReturn(mockStatus);

        // Act & Assert
        mockMvc.perform(get(API_ENDPOINT + "/" + INSTANCE_ID + "/status")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceState", is("running")))
                .andExpect(jsonPath("$.systemStatus", is("initializing")))
                .andExpect(jsonPath("$.instanceStatus", is("initializing")));

        verify(ec2Service).getInstanceStatus(INSTANCE_ID, REGION);
    }

    @Test
    @DisplayName("Should return 400 when region is missing for get instance status")
    void getInstanceStatus_MissingRegion_ReturnsBadRequest() throws Exception {
        // Act & Assert
        mockMvc.perform(get(API_ENDPOINT + "/" + INSTANCE_ID + "/status")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(ec2Service, never()).getInstanceStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("Should handle service exception for get instance status")
    void getInstanceStatus_ServiceThrowsException_ReturnsServerError() throws Exception {
        // Arrange
        when(ec2Service.getInstanceStatus(eq(INSTANCE_ID), eq(REGION)))
                .thenThrow(new RuntimeException("Instance not found"));

        // Act & Assert
        mockMvc.perform(get(API_ENDPOINT + "/" + INSTANCE_ID + "/status")
                        .param("region", REGION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError());

        verify(ec2Service, times(1)).getInstanceStatus(eq(INSTANCE_ID), eq(REGION));
    }
}

