package com.bms.backend.entity;

import javax.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;


@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //  客户名称
    @Column(nullable = false , unique = true , length = 128)
    private String name;

    // 逻辑删除
    @Builder.Default
    @Column(nullable = false)
    private Boolean deleted = false;

    // 备注
    @Column
    private String remark;

    // 创建时间
    @Column(name = "created_at" , nullable = false , columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    // 最后更新时间
    @Column(name = "updated_at" , nullable = false , columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime updatedAt;

    // 生命周期回调方法
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
