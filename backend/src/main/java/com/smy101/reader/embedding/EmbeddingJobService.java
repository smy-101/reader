package com.smy101.reader.embedding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smy101.reader.book.Book;
import com.smy101.reader.book.BookMapper;
import com.smy101.reader.book.BookUploadedEvent;
import com.smy101.reader.book.Chapter;
import com.smy101.reader.book.ChapterMapper;
import com.smy101.reader.config.ReaderProperties;
import com.smy101.reader.embedding.dto.EmbeddingDtos;
import com.smy101.reader.llm.EmbeddingsClient;
import com.smy101.reader.llm.LlmException;
import com.smy101.reader.settings.ModelSettings;
import com.smy101.reader.settings.ModelSettingsMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 嵌入任务编排(M4-02,FR-302 基建):上传后自动建任务、手动触发一入口多态
 * (首次嵌入 / 失败重试 / 换模型全量重嵌入),后台<b>单线程队列</b>执行、同书串行。
 * <p>
 * 执行 = 切块({@link ChapterChunker})→ 分批调 embeddings({@link EmbeddingsClient},
 * embedding 独立 base_url/api_key,D-28)→ 批量入库 → 增量更新进度 → done;
 * 重试与重嵌入都从头重跑(先清该书旧块,幂等,不做断点续传)。
 * 嵌入失败不影响上传结果与正常阅读(增益不是门槛);书被删则任务经外键级联清净,
 * 运行中的失败落库为无操作,不留尸块。
 */
@Slf4j
@Service
public class EmbeddingJobService {

    private final EmbeddingJobMapper jobMapper;
    private final BookMapper bookMapper;
    private final ChapterMapper chapterMapper;
    private final ModelSettingsMapper settingsMapper;
    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingsClient embeddingsClient;
    private final int batchSize;

    /** 待执行书队列(同书去重:在队即不再入队,串行语义吸收并发触发)。 */
    private final LinkedBlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private final Set<Long> enqueued = ConcurrentHashMap.newKeySet();
    /** 单线程 worker:全局串行,个人量级足够,无并发竞态。 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "embedding-worker");
        t.setDaemon(true);
        return t;
    });

    public EmbeddingJobService(EmbeddingJobMapper jobMapper,
                               BookMapper bookMapper,
                               ChapterMapper chapterMapper,
                               ModelSettingsMapper settingsMapper,
                               DocumentChunkRepository chunkRepository,
                               EmbeddingsClient embeddingsClient,
                               ReaderProperties properties) {
        this.jobMapper = jobMapper;
        this.bookMapper = bookMapper;
        this.chapterMapper = chapterMapper;
        this.settingsMapper = settingsMapper;
        this.chunkRepository = chunkRepository;
        this.embeddingsClient = embeddingsClient;
        this.batchSize = Math.max(1, properties.getLlm().getEmbeddingBatchSize());
        this.worker.execute(this::loop);
        recoverInterruptedJobs();
    }

    /**
     * 启动恢复(评审 P1):队列在内存,后端重启会丢 pending/running 任务的执行机会——
     * 任务行永远停在非终态,trigger 又被串行语义吸收,状态卡会无限“嵌入中”。
     * 恢复 = 把非终态任务重置回 pending(执行本就从0重跑)并重新入队;书已删则入队后无操作。
     */
    private void recoverInterruptedJobs() {
        try {
            List<Long> bookIds = jobMapper.selectList(new LambdaQueryWrapper<EmbeddingJob>()
                            .select(EmbeddingJob::getBookId)
                            .in(EmbeddingJob::getStatus, EmbeddingJob.STATUS_PENDING, EmbeddingJob.STATUS_RUNNING))
                    .stream().map(EmbeddingJob::getBookId).distinct().toList();
            for (Long bookId : bookIds) {
                jobMapper.update(null, new LambdaUpdateWrapper<EmbeddingJob>()
                        .eq(EmbeddingJob::getBookId, bookId)
                        .in(EmbeddingJob::getStatus, EmbeddingJob.STATUS_PENDING, EmbeddingJob.STATUS_RUNNING)
                        .set(EmbeddingJob::getStatus, EmbeddingJob.STATUS_PENDING)
                        .setSql("updated_at = now()"));
                enqueue(bookId);
            }
            if (!bookIds.isEmpty()) {
                log.info("启动恢复:重新入队 {} 本书的未完成嵌入任务", bookIds.size());
            }
        } catch (Exception e) {
            log.warn("启动恢复嵌入任务失败(不影响应用启动)", e);
        }
    }

    // ---- 上传自动嵌入(嵌入是增益:任何异常不冒泡到上传链路) ----

    @EventListener
    public void onBookUploaded(BookUploadedEvent event) {
        try {
            if (EmbeddingEndpoint.from(settingsMapper.selectById(1)) != null) {
                createJobIfNeeded(event.bookId());
            }
        } catch (Exception e) {
            log.warn("上传后自动创建嵌入任务失败 bookId={}", event.bookId(), e);
        }
    }

    // ---- 触发端点(一入口多态) ----

    /**
     * 对书触发嵌入:未嵌入/failed → 新任务从头重跑;pending/running → 幂等返回当前状态
     * (并发触发被任务串行语义吸收);done 且模型未变 → 幂等返回;done 但模型已变 → 全量重嵌入。
     */
    public synchronized EmbeddingDtos.StatusDto trigger(long bookId) {
        requireBook(bookId);
        EmbeddingEndpoint endpoint = EmbeddingEndpoint.from(settingsMapper.selectById(1));
        if (endpoint == null) {
            throw new IllegalArgumentException("尚未配置 embedding 模型:请先在 AI 设置页填写后再触发嵌入");
        }
        return createJobIfNeeded(bookId);
    }

    /** 状态查询:每书读最新一条任务;无任务 = none。 */
    public EmbeddingDtos.StatusDto status(long bookId) {
        requireBook(bookId);
        EmbeddingJob latest = latestJob(bookId);
        return latest == null ? EmbeddingDtos.StatusDto.none(bookId) : toDto(latest);
    }

    /** 某书最新一条任务(嵌入就绪度裁决与状态查询共用,避免两处重复解读同一查询)。 */
    public EmbeddingJob latestJob(long bookId) {
        return jobMapper.selectOne(new LambdaQueryWrapper<EmbeddingJob>()
                .eq(EmbeddingJob::getBookId, bookId)
                .orderByDesc(EmbeddingJob::getId)
                .last("LIMIT 1"));
    }

    /**
     * 全库就绪书集合(S4 跨书检索范围,D-36):与 S3 单书"嵌入完成"同一裁决口径的集合化;
     * 未嵌入/进行中/失败/模型已换均静默排除。
     */
    public Set<Long> readyBookIds() {
        String currentModel = currentModel();
        if (currentModel == null) {
            return Set.of();
        }
        return jobMapper.selectLatestPerBook().stream()
                .filter(job -> isReady(job, currentModel))
                .map(EmbeddingJob::getBookId)
                .collect(Collectors.toSet());
    }

    /**
     * 书库列表项的嵌入就绪摘要(US 25):每书最新任务状态 + 是否就绪,一次查询全库拿齐;
     * 前端入口显隐与状态卡同源消费。embedding 未配置时全部 ready=false。
     */
    public Map<Long, EmbeddingDtos.EmbeddingSummary> embeddingSummaries() {
        String currentModel = currentModel();
        Map<Long, EmbeddingJob> latest = jobMapper.selectLatestPerBook().stream()
                .collect(Collectors.toMap(EmbeddingJob::getBookId, job -> job));
        return bookMapper.selectList(null).stream()
                .collect(Collectors.toMap(
                        Book::getId,
                        book -> {
                            EmbeddingJob job = latest.get(book.getId());
                            return new EmbeddingDtos.EmbeddingSummary(
                                    job == null ? "none" : job.getStatus(),
                                    job == null ? null : job.getModel(),
                                    job != null && isReady(job, currentModel));
                        }));
    }

    /** 就绪裁决(单一口径,S3 单书与 S4 全库同源):最新任务 done 且模型与当前 embedding 配置一致;
     * currentModel 空 = 未配置(未就绪)。 */
    public static boolean isReady(EmbeddingJob job, String currentModel) {
        return currentModel != null
                && EmbeddingJob.STATUS_DONE.equals(job.getStatus())
                && currentModel.equals(job.getModel());
    }

    // ---- 内部:任务创建与执行 ----

    /** 建任务规则与 {@link #trigger} 同;已排队/进行中/同模型已完成的幂等跳过。
     * synchronized:上传自动建任务与手动触发并发时不会重复建 pending 行(串行裁决单一入口)。 */
    private synchronized EmbeddingDtos.StatusDto createJobIfNeeded(long bookId) {
        EmbeddingJob latest = latestJob(bookId);
        if (latest != null) {
            if (EmbeddingJob.STATUS_PENDING.equals(latest.getStatus())
                    || EmbeddingJob.STATUS_RUNNING.equals(latest.getStatus())) {
                return toDto(latest); // 串行语义吸收并发触发
            }
            if (EmbeddingJob.STATUS_DONE.equals(latest.getStatus()) && modelUnchanged(latest)) {
                return toDto(latest); // 幂等:同模型已完成
            }
        }
        EmbeddingJob job = new EmbeddingJob();
        job.setBookId(bookId);
        job.setModel(currentModel());
        job.setStatus(EmbeddingJob.STATUS_PENDING);
        job.setChunkDone(0);
        job.setChunkTotal(0);
        jobMapper.insert(job);
        enqueue(bookId);
        return toDto(jobMapper.selectById(job.getId()));
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Long bookId = queue.take();
                try {
                    runBook(bookId);
                } catch (Exception e) {
                    log.error("嵌入任务执行异常 bookId={}", bookId, e);
                } finally {
                    enqueued.remove(bookId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void enqueue(long bookId) {
        if (enqueued.add(bookId)) {
            queue.add(bookId);
        }
    }

    /** 执行该书最新 pending 任务(同书串行:单 worker + 队列去重保证)。 */
    private void runBook(long bookId) {
        EmbeddingJob job = latestJob(bookId);
        if (job == null || !EmbeddingJob.STATUS_PENDING.equals(job.getStatus())) {
            return; // 书已删(任务随级联消失)或已被处理
        }
        EmbeddingEndpoint endpoint = EmbeddingEndpoint.from(settingsMapper.selectById(1));
        if (endpoint == null) {
            update(job.getId(), u -> u.set(EmbeddingJob::getStatus, EmbeddingJob.STATUS_FAILED)
                    .set(EmbeddingJob::getError, "尚未配置 embedding 模型:请先在 AI 设置页填写后重新触发"));
            return;
        }
        // 模型在运行开始时定格:done 裁决(job.model = 当前配置)始终一致
        update(job.getId(), u -> u.set(EmbeddingJob::getStatus, EmbeddingJob.STATUS_RUNNING)
                .set(EmbeddingJob::getModel, endpoint.model())
                .set(EmbeddingJob::getChunkDone, 0)
                .set(EmbeddingJob::getError, null));

        try {
            execute(job.getId(), bookId, endpoint);
        } catch (LlmException e) {
            log.warn("嵌入任务上游失败 bookId={}: {}", bookId, e.getMessage());
            update(job.getId(), u -> u.set(EmbeddingJob::getStatus, EmbeddingJob.STATUS_FAILED)
                    .set(EmbeddingJob::getError, e.getMessage()));
        } catch (DataAccessException e) {
            // 多为书被删后的外键拒绝:任务行已随书级联,更新为无操作;否则记失败
            log.warn("嵌入任务数据访问失败 bookId={}: {}", bookId, e.getMessage());
            update(job.getId(), u -> u.set(EmbeddingJob::getStatus, EmbeddingJob.STATUS_FAILED)
                    .set(EmbeddingJob::getError, "嵌入任务执行失败:书数据不可用"));
        }
    }

    private void execute(long jobId, long bookId, EmbeddingEndpoint endpoint) {
        // 从头重跑:先清该书旧块(幂等)
        chunkRepository.deleteByBookId(bookId);

        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getBookId, bookId)
                .orderByAsc(Chapter::getSeq));

        List<DocumentChunkRepository.ChunkRow> all = new ArrayList<>();
        for (Chapter chapter : chapters) {
            List<TextChunk> textChunks = ChapterChunker.chunk(chapter.getContent());
            for (int i = 0; i < textChunks.size(); i++) {
                TextChunk textChunk = textChunks.get(i);
                all.add(new DocumentChunkRepository.ChunkRow(
                        chapter.getId(), i + 1, textChunk.content(), textChunk.tokenCount(), null));
            }
        }
        final int total = all.size();
        update(jobId, u -> u.set(EmbeddingJob::getChunkTotal, total));

        for (int start = 0; start < total; start += batchSize) {
            List<DocumentChunkRepository.ChunkRow> pending =
                    all.subList(start, Math.min(start + batchSize, total));
            List<float[]> vectors = embeddingsClient.embed(new EmbeddingsClient.EmbeddingRequest(
                    endpoint.baseUrl(),
                    endpoint.apiKey(),
                    endpoint.model(),
                    pending.stream().map(DocumentChunkRepository.ChunkRow::content).toList()));
            if (vectors.size() != pending.size()) {
                throw new LlmException("Embedding 服务返回向量数(" + vectors.size()
                        + ")与输入块数(" + pending.size() + ")不一致");
            }
            List<DocumentChunkRepository.ChunkRow> rows = new ArrayList<>(pending.size());
            for (int i = 0; i < pending.size(); i++) {
                DocumentChunkRepository.ChunkRow row = pending.get(i);
                rows.add(new DocumentChunkRepository.ChunkRow(
                        row.chapterId(), row.seq(), row.content(), row.tokenCount(), vectors.get(i)));
            }
            chunkRepository.insertBatch(bookId, rows);
            final int done = start + rows.size();
            update(jobId, u -> u.set(EmbeddingJob::getChunkDone, done));
        }

        update(jobId, u -> u.set(EmbeddingJob::getStatus, EmbeddingJob.STATUS_DONE)
                .set(EmbeddingJob::getError, null));
    }

    // ---- 小工具 ----

    private boolean modelUnchanged(EmbeddingJob job) {
        String current = currentModel();
        return current != null && current.equals(job.getModel());
    }

    private String currentModel() {
        ModelSettings settings = settingsMapper.selectById(1);
        return settings == null ? null : settings.getEmbeddingModel();
    }

    private void update(long jobId, java.util.function.Consumer<LambdaUpdateWrapper<EmbeddingJob>> setter) {
        LambdaUpdateWrapper<EmbeddingJob> wrapper = new LambdaUpdateWrapper<EmbeddingJob>()
                .eq(EmbeddingJob::getId, jobId);
        setter.accept(wrapper);
        jobMapper.update(null, wrapper.setSql("updated_at = now()")); // 服务器时钟(D-19)
    }

    private EmbeddingDtos.StatusDto toDto(EmbeddingJob job) {
        return new EmbeddingDtos.StatusDto(
                job.getBookId(), job.getStatus(), job.getModel(),
                job.getChunkDone(), job.getChunkTotal(), job.getError(), job.getUpdatedAt());
    }

    private void requireBook(long bookId) {
        if (bookMapper.selectById(bookId) == null) {
            throw new NoSuchElementException("书籍不存在");
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
