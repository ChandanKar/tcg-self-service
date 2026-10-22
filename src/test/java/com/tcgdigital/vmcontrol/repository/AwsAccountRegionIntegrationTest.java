package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.service.aws.AwsRegionService;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.Region;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** V96 (U19-T01): each account's enabled regions are stored, re-read on refresh, and go with the account. */
class AwsAccountRegionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AwsAccountRegionRepository regions;
    @Autowired private AwsAccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;

    private String memberRef;

    @AfterEach
    void removeMember() {
        if (memberRef != null && accounts.existsById(memberRef)) {
            accounts.deleteById(memberRef);
        }
    }

    private static Ec2Client awsSays(String... codes) {
        Ec2Client ec2 = mock(Ec2Client.class);
        List<Region> list = java.util.Arrays.stream(codes)
                .map(c -> Region.builder().regionName(c).optInStatus(c.startsWith("me-") ? "opted-in" : "opt-in-not-required").build())
                .toList();
        when(ec2.describeRegions(any(DescribeRegionsRequest.class))).thenReturn(DescribeRegionsResponse.builder().regions(list).build());
        return ec2;
    }

    private AwsRegionService serviceWith(Ec2Client ec2) {
        return new com.tcgdigital.vmcontrol.service.aws.AwsRegionServiceTestAccess().create(regions, ec2);
    }

    @Test
    void enabledRegionsAreStoredAndARefreshReplacesThem() {
        AwsRegionService service = serviceWith(awsSays("us-east-1", "ap-south-1", "me-central-1"));

        assertThat(service.enabledRegions(AwsAccount.DEFAULT_REF)).contains(List.of("ap-south-1", "me-central-1", "us-east-1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aws_account_region WHERE aws_account_ref = ?", Integer.class,
                AwsAccount.DEFAULT_REF)).isEqualTo(3);

        service.recordRefusal(AwsAccount.DEFAULT_REF, "ap-south-1", "UnauthorizedOperation");
        AwsRegionService later = serviceWith(awsSays("us-east-1", "ap-south-1", "eu-south-2"));
        assertThat(later.refresh(AwsAccount.DEFAULT_REF)).containsExactly("ap-south-1", "eu-south-2", "us-east-1");

        assertThat(regions.findByAwsAccountRefOrderByRegionCodeAsc(AwsAccount.DEFAULT_REF))
                .extracting(r -> r.getRegionCode()).containsExactly("ap-south-1", "eu-south-2", "us-east-1");
        // The refusal outlives the refresh; the region stays out for its day.
        assertThat(later.refusedRegions(AwsAccount.DEFAULT_REF)).containsExactly("ap-south-1");
    }

    @Test
    void anAccountsRegionsGoWithIt() {
        AwsAccount member = new AwsAccount();
        member.setDisplayName("Region test member");
        member.setRoleArn("arn:aws:iam::210987654321:role/VmControlAccess");
        memberRef = accounts.saveAndFlush(member).getAwsAccountRef();
        serviceWith(awsSays("eu-west-1")).refresh(memberRef);
        assertThat(regions.findByAwsAccountRefOrderByRegionCodeAsc(memberRef)).hasSize(1);

        accounts.deleteById(memberRef);
        accounts.flush();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aws_account_region WHERE aws_account_ref = ?", Integer.class, memberRef))
                .isZero();
    }
}
