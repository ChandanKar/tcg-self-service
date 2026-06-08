package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.VmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwsCloudProviderServiceTest {

    @Mock
    private Ec2Client mockEc2Client;

    private AwsCloudProviderService service;

    private static final String INSTANCE_ID = "i-1234567890abcdef0";
    private static final String REGION = "us-east-1";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AwsCloudProviderService();
        ReflectionTestUtils.setField(service, "accessKey", "test-key");
        ReflectionTestUtils.setField(service, "secretKey", "test-secret");
        ReflectionTestUtils.setField(service, "defaultRegion", REGION);
        // Disable sleep delays in polling loops
        ReflectionTestUtils.setField(service, "statusCheckPollIntervalMs", 0);
        ReflectionTestUtils.setField(service, "stopPollIntervalMs", 0);

        // Inject mock client directly into the cache so no real client is created
        Map<String, Ec2Client> clientCache =
                (Map<String, Ec2Client>) ReflectionTestUtils.getField(service, "clientCache");
        clientCache.put(REGION, mockEc2Client);
    }

    // --- helpers ---

    private StartInstancesResponse startResponse(InstanceStateName previous, InstanceStateName current) {
        return StartInstancesResponse.builder()
                .startingInstances(InstanceStateChange.builder()
                        .instanceId(INSTANCE_ID)
                        .previousState(InstanceState.builder().name(previous).build())
                        .currentState(InstanceState.builder().name(current).build())
                        .build())
                .build();
    }

    private StartInstancesResponse emptyStartResponse() {
        return StartInstancesResponse.builder().build();
    }

    private DescribeInstanceStatusResponse statusChecksOk() {
        return DescribeInstanceStatusResponse.builder()
                .instanceStatuses(InstanceStatus.builder()
                        .instanceId(INSTANCE_ID)
                        .instanceState(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                        .systemStatus(InstanceStatusSummary.builder().status(SummaryStatus.OK).build())
                        .instanceStatus(InstanceStatusSummary.builder().status(SummaryStatus.OK).build())
                        .build())
                .build();
    }

    private DescribeInstancesResponse describeResponse(InstanceStateName stateName) {
        return DescribeInstancesResponse.builder()
                .reservations(Reservation.builder()
                        .instances(Instance.builder()
                                .instanceId(INSTANCE_ID)
                                .state(InstanceState.builder().name(stateName).build())
                                .build())
                        .build())
                .build();
    }

    private Ec2Exception ec2Exception(String errorCode, String message) {
        return (Ec2Exception) Ec2Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode(errorCode)
                        .errorMessage(message)
                        .build())
                .build();
    }

    // --- startVm: happy path ---

    @Test
    void startVm_normalFlow_returnsSuccess() throws ExecutionException, InterruptedException {
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(startResponse(InstanceStateName.STOPPED, InstanceStateName.PENDING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(statusChecksOk());

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.RUNNING, result.getResultStatus());
    }

    // --- startVm: empty startingInstances recovery ---

    @Test
    void startVm_emptyStartingInstances_instanceAlreadyPending_recoversAsSuccess()
            throws ExecutionException, InterruptedException {
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(emptyStartResponse());
        // getVmStatus uses describeInstances
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.PENDING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(statusChecksOk());

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.RUNNING, result.getResultStatus());
    }

    @Test
    void startVm_emptyStartingInstances_instanceAlreadyRunning_recoversAsSuccess()
            throws ExecutionException, InterruptedException {
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(emptyStartResponse());
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.RUNNING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(statusChecksOk());

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertTrue(result.isSuccess());
    }

    @Test
    void startVm_emptyStartingInstances_instanceStopped_returnsFailure()
            throws ExecutionException, InterruptedException {
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(emptyStartResponse());
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.STOPPED));

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("No instance state change returned"));
    }

    // --- startVm: IncorrectInstanceState exception recovery ---

    @Test
    void startVm_incorrectInstanceState_instanceAlreadyPending_recoversAsSuccess()
            throws ExecutionException, InterruptedException {
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenThrow(ec2Exception("IncorrectInstanceState", "Instance is already in pending state"));
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.PENDING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(statusChecksOk());

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.RUNNING, result.getResultStatus());
    }

    @Test
    void startVm_incorrectInstanceState_instanceAlreadyRunning_recoversAsSuccess()
            throws ExecutionException, InterruptedException {
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenThrow(ec2Exception("IncorrectInstanceState", "Instance is already running"));
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.RUNNING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(statusChecksOk());

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertTrue(result.isSuccess());
    }

    @Test
    void startVm_incorrectInstanceState_instanceInUnexpectedState_returnsFailure()
            throws ExecutionException, InterruptedException {
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenThrow(ec2Exception("IncorrectInstanceState", "Instance is terminated"));
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.TERMINATED));

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("AWS Error:"));
    }

    // --- startVm: other AWS errors always fail ---

    @Test
    void startVm_otherEc2Exception_returnsFailure()
            throws ExecutionException, InterruptedException {
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenThrow(ec2Exception("UnauthorizedOperation",
                        "You are not authorized to perform this operation"));

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("AWS Error:"));
        assertTrue(result.getMessage().contains("not authorized"));
    }

    // --- getVmStatus ---

    @Test
    void getVmStatus_returnsRunning() {
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.RUNNING));

        assertEquals(VmStatus.RUNNING, service.getVmStatus(INSTANCE_ID, REGION));
    }

    @Test
    void getVmStatus_instanceNotFound_returnsNotFound() {
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenThrow(ec2Exception("InvalidInstanceID.NotFound", "Instance not found"));

        assertEquals(VmStatus.NOT_FOUND, service.getVmStatus(INSTANCE_ID, REGION));
    }
}
