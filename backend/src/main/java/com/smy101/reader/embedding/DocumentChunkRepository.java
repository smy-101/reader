package com.smy101.reader.embedding;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 向量块仓储(M4-02):vector 列(pgvector)不走 MyBatis-Plus,统一经 JdbcTemplate
 * 显式 SQL,向量以字面量文本 + ::vector 传参——向量读写只此一处,维度不设 typmod(R-3)。
 */
@Repository
@RequiredArgsConstructor
public class DocumentChunkRepository {

    private final JdbcTemplate jdbc;

    /** 待入库的块(章内序号由调用方推进)。 */
    public record ChunkRow(long chapterId, int seq, String content, int tokenCount, float[] embedding) {
    }

    /** 检索命中块(带章节与书籍溯源信息,S3 引用跳转与 S4 跨书引用用)。 */
    public record ChunkHit(long bookId, String bookTitle,
                           long chapterId, int seq, String content, int tokenCount,
                           String chapterTitle, Integer chapterSeq) {
    }

    /** 清某书全部块(重试/重嵌入从头重跑的第一步;删书级联由外键兜底)。 */
    public void deleteByBookId(long bookId) {
        jdbc.update("DELETE FROM document_chunk WHERE book_id = ?", bookId);
    }

    /** 某书块条数(测试与状态核验)。 */
    public int countByBookId(long bookId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE book_id = ?", Integer.class, bookId);
        return count == null ? 0 : count;
    }

    /** 某书全部块的向量维度集合(同书维度一致性断言用)。 */
    public List<Integer> embeddingDimensions(long bookId) {
        return jdbc.queryForList(
                "SELECT vector_dims(embedding) FROM document_chunk WHERE book_id = ?",
                Integer.class, bookId);
    }

    /** 批量入库(embedding 批次粒度);书被删则外键拒绝,由调用方按取消处理。 */
    public void insertBatch(long bookId, List<ChunkRow> rows) {
        jdbc.batchUpdate(
                "INSERT INTO document_chunk (book_id, chapter_id, seq, content, token_count, embedding) "
                        + "VALUES (?, ?, ?, ?, ?, ?::vector)",
                rows,
                64,
                (PreparedStatement ps, ChunkRow row) -> {
                    try {
                        ps.setLong(1, bookId);
                        ps.setLong(2, row.chapterId());
                        ps.setInt(3, row.seq());
                        ps.setString(4, row.content());
                        ps.setInt(5, row.tokenCount());
                        ps.setString(6, toVectorLiteral(row.embedding()));
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    /**
     * 按书集合 cosine 检索 top-k(ADR-0008:个人量级顺序扫描,<=> 为 cosine 距离)。
     * S3 单书与 S4 多书共用同一条查询路径:bookIds 为空 = 不过滤(全库);
     * 命中携带书籍身份(bookId + 书名,S4 跨书引用与 D-33 书名快照用)。
     */
    public List<ChunkHit> searchTopK(Collection<Long> bookIds, float[] queryVector, int k) {
        List<Object> params = new ArrayList<>(bookIds.size() + 2);
        StringBuilder in = new StringBuilder();
        if (!bookIds.isEmpty()) {
            in.append("WHERE c.book_id IN (");
            for (Long bookId : bookIds) {
                if (params.size() > 0) {
                    in.append(',');
                }
                in.append('?');
                params.add(bookId);
            }
            in.append(") ");
        }
        params.add(toVectorLiteral(queryVector));
        params.add(k);
        return jdbc.query(
                "SELECT c.book_id, b.title AS book_title, c.chapter_id, c.seq, c.content, c.token_count, "
                        + "ch.title AS chapter_title, ch.seq AS chapter_seq "
                        + "FROM document_chunk c "
                        + "JOIN chapter ch ON ch.id = c.chapter_id "
                        + "JOIN book b ON b.id = c.book_id "
                        + in
                        + "ORDER BY c.embedding <=> ?::vector ASC "
                        + "LIMIT ?",
                (rs, i) -> new ChunkHit(
                        rs.getLong("book_id"),
                        rs.getString("book_title"),
                        rs.getLong("chapter_id"),
                        rs.getInt("seq"),
                        rs.getString("content"),
                        rs.getInt("token_count"),
                        rs.getString("chapter_title"),
                        rs.getInt("chapter_seq")),
                params.toArray());
    }

    /** float[] → pgvector 字面量 '[v1,v2,...]'(足够精度,确定性)。 */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
