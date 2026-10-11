package com.tcgdigital.vmcontrol.dto;

public class EksClusterInfoDTO {

    private String clusterName;
    private String region;
    /** The account the cluster was found in (E28-T06). */
    private String accountRef;
    private String accountName;

    public EksClusterInfoDTO() {}

    public EksClusterInfoDTO(String clusterName, String region) {
        this.clusterName = clusterName;
        this.region = region;
    }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public EksClusterInfoDTO(String clusterName, String region, String accountRef, String accountName) {
        this(clusterName, region);
        this.accountRef = accountRef;
        this.accountName = accountName;
    }

    public String getRegion() { return region; }
    public String getAccountRef() { return accountRef; }
    public void setAccountRef(String accountRef) { this.accountRef = accountRef; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public void setRegion(String region) { this.region = region; }
}
