package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.sql.Timestamp;

/** A carbon export file already ingested (E37-T01): the same key and ETag is not read again. */
@Entity
@Table(name = "carbon_ingest_file")
public class CarbonIngestFile {

    @Id
    @Column(name = "s3_key", length = 768)
    private String s3Key;

    @Column(name = "etag", nullable = false, length = 100)
    private String etag;

    @Column(name = "ingested_at", nullable = false)
    private Timestamp ingestedAt;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    public CarbonIngestFile() {
    }

    public CarbonIngestFile(String s3Key, String etag, Timestamp ingestedAt, int rowCount) {
        this.s3Key = s3Key;
        this.etag = etag;
        this.ingestedAt = ingestedAt;
        this.rowCount = rowCount;
    }

    public String getS3Key() { return s3Key; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public Timestamp getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Timestamp ingestedAt) { this.ingestedAt = ingestedAt; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
}
