package com.bms.backend.repository;

import com.bms.backend.entity.BatteryCsvUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BatteryCsvUploadRepository extends JpaRepository<BatteryCsvUpload , Long> {

    Optional<BatteryCsvUpload> findByUploadToken(String uploadToken);

    // 按usedAt倒序取电池最新一次绑定的上传记录
    Optional<BatteryCsvUpload> findTopByBatteryIdOrderByUsedAtDesc(Long batteryId);
}
