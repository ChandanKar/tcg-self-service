package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.repository.AwsAccountRegionRepository;
import software.amazon.awssdk.services.ec2.Ec2Client;

/** Builds an AwsRegionService on a fixed EC2 client for tests in other packages. */
public class AwsRegionServiceTestAccess {

    public AwsRegionService create(AwsAccountRegionRepository repository, Ec2Client ec2) {
        return new AwsRegionService(repository, (ref, region) -> ec2, () -> "ap-south-1", () -> true);
    }
}
