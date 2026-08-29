package com.smy101.reader.embedding;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 嵌入任务:对一本书全量生成向量块的后台作业;表内存历史,对外每书读最新一条。 */
@Data
@TableName("embedding_job")
public class EmbeddingJob {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookId;

    /** 本次任务嵌入所用模型(运行开始时取当前配置,保证与 done 裁决一致) */
    private String model;

    /** pending / running / done / failed */
    private String status;

    private Integer chunkDone;

    private Integer chunkTotal;

    /** 失败时的可读中文错误;成功后清空 */
    private String error;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
