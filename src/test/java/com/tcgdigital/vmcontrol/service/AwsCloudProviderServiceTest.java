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

import java.util.ArrayList;
import java.util.List;
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
        ReflectionTestUtils.setField(service, "statusCheckTimeoutMs", 50);
        ReflectionTestUtils.setField(service, "statusCheckPollIntervalMs", 1);
        ReflectionTestUtils.setField(service, "stopPollIntervalMs", 0);

        // Inject mock client directly into the cache so no real client is created
        Map<String, Ec2Client> clientCache =
                (Map<String, Ec2Client>) ReflectionTestUtils.getField(service, "clientCache");
        clientCache.put(REGION, mockEc2Client);
    }

    // --- discovery (E09-T02, H24): a failed call is reported, never an empty list ---

    @Test
    void discoverTaggedInstancesReportsAFailedCall() {
        when(mockEc2Client.describeInstancesPaginator(any(DescribeInstancesRequest.class)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("Rate exceeded"));

        assertThrows(com.tcgdigital.vmcontrol.exception.DiscoveryFailedException.class,
                () -> service.discoverTaggedInstances(REGION, "tcg:environment", "app"));
    }

    @Test
    void discoverInstancesByNamePrefixReportsAFailedCall() {
        when(mockEc2Client.describeInstancesPaginator(any(DescribeInstancesRequest.class)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("Rate exceeded"));

        assertThrows(com.tcgdigital.vmcontrol.exception.DiscoveryFailedException.class,
                () -> service.discoverInstancesByNamePrefix(REGION, "app"));
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

    private StopInstancesResponse stopResponse(InstanceStateName previous, InstanceStateName current) {
        return StopInstancesResponse.builder()
                .stoppingInstances(InstanceStateChange.builder()
                        .instanceId(INSTANCE_ID)
                        .previousState(InstanceState.builder().name(previous).build())
                        .currentState(InstanceState.builder().name(current).build())
                        .build())
                .build();
    }

    private StopInstancesResponse emptyStopResponse() {
        return StopInstancesResponse.builder().build();
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

    private DescribeInstanceStatusResponse statusChecksOkWithAttachedEbs() {
        return DescribeInstanceStatusResponse.builder()
                .instanceStatuses(InstanceStatus.builder()
                        .instanceId(INSTANCE_ID)
                        .instanceState(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                        .systemStatus(InstanceStatusSummary.builder().status(SummaryStatus.OK).build())
                        .instanceStatus(InstanceStatusSummary.builder().status(SummaryStatus.OK).build())
                        .attachedEbsStatus(EbsStatusSummary.builder().status(SummaryStatus.OK).build())
                        .build())
                .build();
    }

    private DescribeInstanceStatusResponse runningWithInitializingChecks() {
        return DescribeInstanceStatusResponse.builder()
                .instanceStatuses(InstanceStatus.builder()
                        .instanceId(INSTANCE_ID)
                        .instanceState(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                        .systemStatus(InstanceStatusSummary.builder().status(SummaryStatus.INITIALIZING).build())
                        .instanceStatus(InstanceStatusSummary.builder().status(SummaryStatus.INITIALIZING).build())
                        .build())
                .build();
    }

    private DescribeInstanceStatusResponse noInstanceStatusYet() {
        return DescribeInstanceStatusResponse.builder().build();
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

    @Test
    void startVm_runningBeforeStatusChecksOk_returnsSuccess() throws ExecutionException, InterruptedException {
        // The instance reaches RUNNING but its status checks never all report OK before the
        // poll times out — that's still a successful start, not a failure (checks routinely
        // take 2-5+ min and shouldn't hold the whole operation hostage).
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(startResponse(InstanceStateName.STOPPED, InstanceStateName.PENDING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(runningWithInitializingChecks());

        List<CloudProviderService.VmOperationProgress> progress = new ArrayList<>();

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION, progress::add).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.RUNNING, result.getResultStatus());
        assertTrue(progress.stream().anyMatch(p ->
                p.getStageLabel() != null
                        && p.getStageLabel().startsWith("AWS checks")
                        && p.getStatus() == VmStatus.STARTING));
    }

    @Test
    void startVm_statusChecksUnavailableButInstanceRunning_returnsSuccess() throws ExecutionException, InterruptedException {
        // Status checks never became available at all, but the instance itself is RUNNING —
        // still a successful start.
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(startResponse(InstanceStateName.STOPPED, InstanceStateName.PENDING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(noInstanceStatusYet());
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.RUNNING));

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.RUNNING, result.getResultStatus());
    }

    @Test
    void startVm_threeStatusChecksOk_returnsSuccess() throws ExecutionException, InterruptedException {
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(startResponse(InstanceStateName.STOPPED, InstanceStateName.PENDING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(statusChecksOkWithAttachedEbs());

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.RUNNING, result.getResultStatus());
    }

    @Test
    void startVm_reportsDynamicStatusCheckProgress() throws ExecutionException, InterruptedException {
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(startResponse(InstanceStateName.STOPPED, InstanceStateName.PENDING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(statusChecksOkWithAttachedEbs());

        List<CloudProviderService.VmOperationProgress> progress = new ArrayList<>();

        CloudProviderService.VmOperationResult result = service
                .startVm(INSTANCE_ID, REGION, progress::add)
                .get();

        assertTrue(result.isSuccess());
        assertTrue(progress.stream().anyMatch(p -> "Start requested".equals(p.getStageLabel())));
        assertTrue(progress.stream().anyMatch(p ->
                "AWS checks 3/3 passed".equals(p.getStageLabel())
                        && p.getProgressPercentage() == 100
                        && p.getStatus() == VmStatus.RUNNING
                        && p.getStatusChecksPassed() == 3
                        && p.getStatusChecksTotal() == 3));
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

    // --- stopVm ---

    @Test
    void stopVm_normalFlow_returnsSuccess() throws ExecutionException, InterruptedException {
        when(mockEc2Client.stopInstances(any(StopInstancesRequest.class)))
                .thenReturn(stopResponse(InstanceStateName.RUNNING, InstanceStateName.STOPPING));
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.STOPPED));

        CloudProviderService.VmOperationResult result = service.stopVm(INSTANCE_ID, REGION, false).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.STOPPED, result.getResultStatus());
    }

    @Test
    void stopVm_emptyStoppingInstances_instanceAlreadyStopping_recoversAsSuccess()
            throws ExecutionException, InterruptedException {
        when(mockEc2Client.stopInstances(any(StopInstancesRequest.class)))
                .thenReturn(emptyStopResponse());
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.STOPPING))
                .thenReturn(describeResponse(InstanceStateName.STOPPED));

        CloudProviderService.VmOperationResult result = service.stopVm(INSTANCE_ID, REGION, false).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.STOPPED, result.getResultStatus());
    }

    @Test
    void stopVm_emptyStoppingInstances_instanceAlreadyStopped_recoversAsSuccess()
            throws ExecutionException, InterruptedException {
        when(mockEc2Client.stopInstances(any(StopInstancesRequest.class)))
                .thenReturn(emptyStopResponse());
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.STOPPED));

        CloudProviderService.VmOperationResult result = service.stopVm(INSTANCE_ID, REGION, false).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.STOPPED, result.getResultStatus());
    }

    @Test
    void stopVm_incorrectInstanceState_instanceAlreadyStopping_recoversAsSuccess()
            throws ExecutionException, InterruptedException {
        when(mockEc2Client.stopInstances(any(StopInstancesRequest.class)))
                .thenThrow(ec2Exception("IncorrectInstanceState", "Instance is already stopping"));
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.STOPPING))
                .thenReturn(describeResponse(InstanceStateName.STOPPED));

        CloudProviderService.VmOperationResult result = service.stopVm(INSTANCE_ID, REGION, false).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.STOPPED, result.getResultStatus());
    }

    @Test
    void stopVm_otherEc2Exception_returnsFailure() throws ExecutionException, InterruptedException {
        when(mockEc2Client.stopInstances(any(StopInstancesRequest.class)))
                .thenThrow(ec2Exception("UnauthorizedOperation",
                        "You are not authorized to perform this operation"));

        CloudProviderService.VmOperationResult result = service.stopVm(INSTANCE_ID, REGION, false).get();

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

    // --- M9: timeouts are failures; a start that falls back to stopped fails fast (E05-T05) ---

    @Test
    void stopVm_stillStoppingAtTheTimeout_isATimedOutFailure() throws ExecutionException, InterruptedException {
        ReflectionTestUtils.setField(service, "stopTimeoutMs", 30L);
        when(mockEc2Client.stopInstances(any(StopInstancesRequest.class)))
                .thenReturn(stopResponse(InstanceStateName.RUNNING, InstanceStateName.STOPPING));
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.STOPPING));

        CloudProviderService.VmOperationResult result = service.stopVm(INSTANCE_ID, REGION, false).get();

        assertFalse(result.isSuccess());
        assertTrue(result.isTimedOut());
        assertEquals(VmStatus.STOPPING, result.getResultStatus());
        assertTrue(result.getMessage().startsWith("Instance still STOPPING after"), result.getMessage());
    }

    @Test
    void stopVm_timeoutMessageNamesTheConfiguredSeconds() throws ExecutionException, InterruptedException {
        // The production default (180000 ms) reads "after 180s"; shown here with 2 s.
        ReflectionTestUtils.setField(service, "stopTimeoutMs", 2000L);
        ReflectionTestUtils.setField(service, "stopPollIntervalMs", 500);
        when(mockEc2Client.stopInstances(any(StopInstancesRequest.class)))
                .thenReturn(stopResponse(InstanceStateName.RUNNING, InstanceStateName.STOPPING));
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.STOPPING));

        assertEquals("Instance still STOPPING after 2s", service.stopVm(INSTANCE_ID, REGION, false).get().getMessage());
    }

    @Test
    void startVm_pendingThenStopped_failsFastInsteadOfPollingForTheFullTimeout()
            throws ExecutionException, InterruptedException {
        ReflectionTestUtils.setField(service, "statusCheckTimeoutMs", 60_000);
        ReflectionTestUtils.setField(service, "statusCheckPollIntervalMs", 5);
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(startResponse(InstanceStateName.STOPPED, InstanceStateName.PENDING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(noInstanceStatusYet());
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.PENDING), describeResponse(InstanceStateName.STOPPED));

        long started = System.currentTimeMillis();
        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertTrue(System.currentTimeMillis() - started < 5_000, "must not wait out the 60 s timeout");
        assertFalse(result.isSuccess());
        assertEquals("Instance returned to STOPPED while starting", result.getMessage());
    }

    @Test
    void startVm_neverLeavingStopped_failsAfterTheGracePeriod() throws ExecutionException, InterruptedException {
        ReflectionTestUtils.setField(service, "statusCheckTimeoutMs", 60_000);
        ReflectionTestUtils.setField(service, "statusCheckPollIntervalMs", 5);
        ReflectionTestUtils.setField(service, "startStoppedGraceMs", 50L);
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(startResponse(InstanceStateName.STOPPED, InstanceStateName.PENDING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(noInstanceStatusYet());
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.STOPPED));

        long started = System.currentTimeMillis();
        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertTrue(System.currentTimeMillis() - started < 5_000, "must not wait out the 60 s timeout");
        assertFalse(result.isSuccess());
        assertEquals("Instance returned to STOPPED while starting", result.getMessage());
    }

    @Test
    void startVm_briefStoppedReadingWithinTheGraceStillSucceeds() throws ExecutionException, InterruptedException {
        // AWS can still report "stopped" right after StartInstances: inside the grace, keep polling.
        ReflectionTestUtils.setField(service, "statusCheckTimeoutMs", 60_000);
        ReflectionTestUtils.setField(service, "statusCheckPollIntervalMs", 1);
        when(mockEc2Client.startInstances(any(StartInstancesRequest.class)))
                .thenReturn(startResponse(InstanceStateName.STOPPED, InstanceStateName.PENDING));
        when(mockEc2Client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class)))
                .thenReturn(noInstanceStatusYet(), noInstanceStatusYet(), statusChecksOk());
        when(mockEc2Client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(describeResponse(InstanceStateName.STOPPED), describeResponse(InstanceStateName.PENDING));

        CloudProviderService.VmOperationResult result = service.startVm(INSTANCE_ID, REGION).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.RUNNING, result.getResultStatus());
    }
}
