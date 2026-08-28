package com.smy101.reader.reading;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReadingProgressMapper extends BaseMapper<ReadingProgress> {

    /**
     * 单行 upsert(D-24):冲突按 book_id 整行覆盖(CFI + percent + 服务器时钟)。
     * 一条原子语句天然 LWW 后写胜(D-19:时钟由 DB now() 统一打,客户端不传时间戳)。
     */
    @Insert("""
            INSERT INTO reading_progress (book_id, cfi, percent, updated_at)
            VALUES (#{bookId}, #{cfi}, #{percent}, now())
            ON CONFLICT (book_id) DO UPDATE
            SET cfi = EXCLUDED.cfi,
                percent = EXCLUDED.percent,
                updated_at = now()
            """)
    int upsert(ReadingProgress progress);
}
