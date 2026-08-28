package com.example.healthreport.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 建任务预检与锁行绑定所需的最小文件快照。
 * <p>不包含原始文件名、对象键或内容哈希，避免无关敏感元数据进入任务链路。</p>
 */
@Data
public class FileBindingRecord {

    private String fileId;
    private String userId;
    private String taskId;
    private String status;
    private Long sizeBytes;
    private Integer precheckPages;
    private LocalDateTime expireAt;
    private String boundTaskStatus;
    private Boolean boundTaskReanalyzable;
}
