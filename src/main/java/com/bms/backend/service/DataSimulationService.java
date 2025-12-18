package com.bms.backend.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.io.Reader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class DataSimulationService {

    @Autowired
    private InfluxDBClient influxDBClient;

    @Value("${influxdb.bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String org;

    // 标记模拟是否正在运行
    private boolean isRunning = false;

    /**
     * 异步执行
     * 启动模拟，但不会阻塞主线程，不会出现Controller卡住，浏览器一直转圈圈儿！
     * 逻辑：读取 CSV -> 循环写入 -> 暂停 -> 再写入   过程可能持续几分钟。
     */
    @Async          // 这里开一个 @Async，调用此方法时，Spring在后台再开一个小灶跑这个方法！
    public void startSimulation(String filePath) {
        // 防止重复启动
        if (isRunning) {
            System.out.println("⚠️ 模拟已经在运行中！");
            return;
        }
        isRunning = true;
        System.out.println("🚀 Java 模拟器启动！开始读取文件：" + filePath);

        try (Reader in = new FileReader(filePath)) {

            // CSV1.1版本后不让弃用了Deprecated的.with....()链式调用，改用Builder模式
            CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .build();

            Iterable<CSVRecord> records = csvFormat.parse(in);

            // 获取同步写入的API，保证数据顺序写入
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();

            // 创建一个列表作为缓冲区(Batch)，凑够500了发一次
            List<Point> batchPoints = new ArrayList<>();
            // 记录开始模拟的系统时间
            Instant simulationStartTime = Instant.now();
            int count = 0;

            // 逐行遍历CSV数据
            for (CSVRecord record : records) {
                // 允许中途停止
                if (!isRunning) {
                    System.out.println("检测停止指令，循环中断");
                    break;
                }
                try {
                    // 1. 提取 CSV 数据
                    double voltage = Double.parseDouble(record.get("Voltage"));
                    double current = Double.parseDouble(record.get("Current"));
                    double temp = Double.parseDouble(record.get("Temp"));
                    double capacity = Double.parseDouble(record.get("Capacity"));
                    double timeMin = Double.parseDouble(record.get("Time_Min")); // 相对时间(分钟)
                    int cycle = Integer.parseInt(record.get("Cycle"));

                    // 2. 计算“伪造”的实时时间戳
                    // 逻辑：当前点时间 = 模拟开始时间 + CSV里的相对分钟数
                    Instant pointTime = simulationStartTime.plusSeconds((long) (timeMin * 60));

                    // 3. 构建 InfluxDB Point
                    Point point = Point.measurement("battery_metrics")
                            .addTag("cell_id", "b1c0")       // Tag，电池单体唯一编号。核心索引
                            .addTag("batch_id", "batch-1")   // Tag，批次编号，用于批量管理。
                            .addTag("cycle_index", String.valueOf(cycle))
                            .addField("voltage", voltage)    // 数值 Field
                            .addField("current", current)
                            .addField("temperature", temp)
                            .addField("capacity", capacity)
                            .time(pointTime, WritePrecision.MS);    // 数据的时间戳

                    // 进缓冲区咯！
                    batchPoints.add(point);

                    // 4. 批量写入 (每 500 条写一次，逻辑：每满500条，向数据库发一次网络请求)
                    if (batchPoints.size() >= 500) {
                        writeApi.writePoints(bucket, org, batchPoints);
                        batchPoints.clear();        // 清空缓冲区
                        System.out.println("   -> 已写入 " + count + " 条数据...");

                        // 暂停个50ms，让前端看到动态的效果。稍微睡一会，模拟真实的时间流逝感
                        // 想快点导，直接去掉这行，起飞！！！
                        Thread.sleep(50);
                    }
                    count++;
                } catch (NumberFormatException e){
                    // 如果某一行数据不对，捕获异常，打印
                    System.out.println("⚠️跳过错误行(格式解析失败)" + record.toString());
                }catch (Exception e){
                    // 这里捕获其他可能的未知异常，防止循环g掉
                    System.err.println("⚠️跳过位置错误行:" + e.getMessage());
                }

            }

            // 5. 结束循环后，写入缓冲区剩下的数据
            if (!batchPoints.isEmpty()) {
                writeApi.writePoints(bucket, org, batchPoints);
            }

            System.out.println("✅ 模拟结束！共写入 " + count + " 条数据。");

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ 模拟过程出错咯！" + e.getMessage());
        } finally {
            isRunning = false;
        }
    }

    /**
     * 停止模拟
     * 将标志位设置为false，上面的循环会在下一次迭代推出
     **/
    public void stopSimulation() {
        if(this.isRunning) {
            this.isRunning = false;
            System.out.println("正在停止模拟...");
        }else {
            System.out.println("模拟器当前并未运行");
        }
    }
}