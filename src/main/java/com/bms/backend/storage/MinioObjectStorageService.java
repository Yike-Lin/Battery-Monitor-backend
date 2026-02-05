package com.bms.backend.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.MinioException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * MinIO实现
 */
@Service
public class MinioObjectStorageService implements ObjectStorageService{

    @Value("${bms.object-storage.endpoint}")
    private String endpoint;

    @Value("${bms.object-storage.access-key}")
    private String accessKey;

    @Value("${bms.object-storage.secret-key}")
    private String secretKey;

    @Value("${bms.object-storage.bucket}")
    private String bucket;

    private MinioClient minioClient;

    // 开机连接建立实例
    @PostConstruct
    public void init() {
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey , secretKey)
                .build();
    }


    /**
     * 上传文件
     * @param uploadToken
     * @param file
     * @return objectName
     */
    @Override
    public String uploadCsv(String uploadToken, MultipartFile file) {
        try {
            // 1. 规划一下文件存储路径（目录隔离）
            // 获取当前日期，分日期存储
            LocalDate today = LocalDate.now();
            // 格式化为"yyyy/MM/dd"
            String datePath = today.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            // 对象存储里的路径
            String objectName = String.format("bms/csv/%s/%s.csv", datePath, uploadToken);

            // 2. 准备数据流（I/O安全）
            // 这里先不用getInputStream()，几百兆还是轻松抗
            byte[] bytes = file.getBytes();
            try(ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
                // 3. 构建MinIO上传参数（SDK调用）
                PutObjectArgs args = PutObjectArgs.builder()
                        .bucket(bucket)     // 指定存在哪个桶
                        .object(objectName)     // 指定存成什么名字
                        .contentType("text/csv")        // 防止MinIO默认识别为二进制流下载
                        .stream(bais, bytes.length, -1)
                        .build();
                // 执行上传操作
                minioClient.putObject(args);
            }
            // 先告诉调用方文件存哪，怎么拼再说！
            return objectName;
        } catch (MinioException e) {
            throw new RuntimeException("上传CSV到MinIO失败：" + e.getMessage() , e);
        } catch (Exception e) {
            throw new RuntimeException("上传CSV失败：" + e.getMessage() , e);
        }
    }

    /**
     * 下载文件
     * @param fileKey
     * @return InputStream
     */
    @Override
    public InputStream downloadCsv(String fileKey) {
        try {
            // 1. 构建下载请求参数
            GetObjectArgs args = GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(fileKey)
                    .build();
            // 2. 过去文件流（网络连接）
            return minioClient.getObject(args);
        } catch (MinioException e) {
            throw new RuntimeException("从MinIO下载CSV失败：" + e.getMessage() , e);
        } catch (Exception e) {
            throw new RuntimeException("下载CSV失败：" + e.getMessage() , e);
        }
    }
}
