package cn.lwccxy.trip.business.conversation.mapper;

import cn.lwccxy.trip.business.conversation.domain.entity.ConversationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
    @Select("SELECT * FROM t_conversation WHERE id=#{id} AND user_id=#{userId}")
    ConversationEntity owned(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT * FROM t_conversation WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    ConversationEntity lockOwned(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_conversation WHERE user_id=#{userId} AND (#{status}='ALL' OR status=#{status})")
    long countOwned(@Param("userId") Long userId, @Param("status") String status);

    @Select("SELECT * FROM t_conversation WHERE user_id=#{userId} AND (#{status}='ALL' OR status=#{status}) "
            + "ORDER BY update_time DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ConversationEntity> pageOwned(@Param("userId") Long userId, @Param("status") String status,
                                      @Param("offset") long offset, @Param("limit") long limit);

    @Update("UPDATE t_conversation SET message_count=message_count+1, "
            + "update_time=GREATEST(update_time,CURRENT_TIMESTAMP(6)), trace_id=#{traceId} WHERE id=#{id} AND user_id=#{userId}")
    int appended(@Param("userId") Long userId, @Param("id") Long id, @Param("traceId") String traceId);

    @Update("UPDATE t_conversation SET title=#{title},status=#{status}, "
            + "update_time=GREATEST(update_time,CURRENT_TIMESTAMP(6)) WHERE id=#{id} AND user_id=#{userId}")
    int change(@Param("userId") Long userId, @Param("id") Long id,
               @Param("title") String title, @Param("status") String status);
}
