package com.smy101.reader.embedding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmbeddingJobMapper extends BaseMapper<EmbeddingJob> {

    /** 每书最新一条任务(book_id 唯一;无任务的书不在结果中)——就绪集合与书库列表摘要共用,免逐书查询。 */
    @Select("SELECT DISTINCT ON (book_id) * FROM embedding_job ORDER BY book_id, id DESC")
    List<EmbeddingJob> selectLatestPerBook();
}
