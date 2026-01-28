package com.bms.backend.entity;


import lombok.*;
import javax.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "battery_model")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatteryModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_code" , nullable = false , unique = true , length = 128)
    private String modelCode;

    @Column
    private String remark;

    @Column(name = "created_at" , nullable = false , columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at" , nullable = false , columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

}
