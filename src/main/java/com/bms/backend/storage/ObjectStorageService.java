package com.bms.backend.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface ObjectStorageService {

    // 上传CSV文件，返回fileKey（对象的存储路径）
    String uploadCsv(String uploadToken , MultipartFile file);

    // 根据fileKey下载CSV内容，返回InputStream
    InputStream downloadCsv(String fileKey);
}
