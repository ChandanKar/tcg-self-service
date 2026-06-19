package com.tcgdigital.vmcontrol.dto;

public class EksClusterInfoDTO {

    private String clusterName;
    private String region;

    public EksClusterInfoDTO() {}

    public EksClusterInfoDTO(String clusterName, String region) {
        this.clusterName = clusterName;
        this.region = region;
    }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
