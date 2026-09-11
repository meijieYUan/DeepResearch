package com.itajay.superassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itajay.superassistant.entity.TodoTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TodoTaskMapper extends BaseMapper<TodoTask> {

    @Select("""
        <script>
        SELECT * FROM todo_task
        WHERE 1=1
        <if test='status != null and status != \"\"'>AND status = #{status}</if>
        <if test='priority != null and priority != \"\"'>AND priority = #{priority}</if>
        <if test='keyword != null and keyword != \"\"'>
            AND (title LIKE CONCAT('%', #{keyword}, '%') OR description LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test='threadId != null and threadId != \"\"'>AND thread_id = #{threadId}</if>
        ORDER BY
            CASE priority WHEN 'URGENT' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 4 END,
            due_date ASC
        </script>
        """)
    List<TodoTask> searchTasks(@Param("status") String status,
                               @Param("priority") String priority,
                               @Param("keyword") String keyword,
                               @Param("threadId") String threadId);

    @Select("SELECT * FROM todo_task WHERE status = #{status} AND due_date < #{date} AND thread_id = #{threadId}")
    List<TodoTask> findOverdue(@Param("status") String status,
                               @Param("date") LocalDateTime date,
                               @Param("threadId") String threadId);
}
