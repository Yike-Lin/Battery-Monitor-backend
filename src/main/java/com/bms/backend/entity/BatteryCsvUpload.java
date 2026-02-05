package com.bms.backend.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "battery_csv_upload")
public class BatteryCsvUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_token" , nullable = false , unique = true , length = 64)
    private String uploadToken;

    @Column(name = "file_name" , length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "content_type" , length = 255)
    private String contentType;

    @Column(name = "file_key" , nullable = false , length = 512)
    private String fileKey;

    @Column(name = "status" , nullable = false ,length = 32)
    private String status;

    @Column(name = "created_at" , nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "battery_id")
    private Long batteryId;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "cycle_count")
    private Integer cycleCount;

}
