package cn.lwccxy.trip.business.conversation.mapper;

import cn.lwccxy.trip.business.conversation.domain.entity.MessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
    @Select("SELECT * FROM t_message WHERE id=#{id} AND conversation_id=#{conversationId} AND user_id=#{userId} FOR UPDATE")
    MessageEntity lockOwned(@Param("userId") Long userId, @Param("conversationId") Long conversationId, @Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_message WHERE conversation_id=#{conversationId} AND user_id=#{userId}")
    long countOwned(@Param("userId") Long userId, @Param("conversationId") Long conversationId);

    @Select("SELECT * FROM t_message WHERE conversation_id=#{conversationId} AND user_id=#{userId} "
            + "ORDER BY create_time ASC,id ASC LIMIT #{limit} OFFSET #{offset}")
    List<MessageEntity> pageOwned(@Param("userId") Long userId, @Param("conversationId") Long conversationId,
                                 @Param("offset") long offset, @Param("limit") long limit);

    /**
     * 查询当前用户会话最近的消息，供意图查询改写补充上下文。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @param limit 最多返回的消息数量
     * @return 按时间倒序排列的最近消息
     */
    @Select("SELECT * FROM t_message WHERE conversation_id=#{conversationId} AND user_id=#{userId} "
            + "ORDER BY create_time DESC,id DESC LIMIT #{limit}")
    List<MessageEntity> recentOwned(@Param("userId") Long userId, @Param("conversationId") Long conversationId,
                                    @Param("limit") int limit);

    // 显式 SQL 保证取消反馈时 NULL 不被 MyBatis-Plus 的默认更新策略忽略。
    @Update("UPDATE t_message SET feedback=#{feedback}, "
            + "feedback_at=CASE WHEN #{feedback} IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END "
            + "WHERE id=#{id} AND conversation_id=#{conversationId} AND user_id=#{userId}")
    int feedback(@Param("userId") Long userId, @Param("conversationId") Long conversationId,
                 @Param("id") Long id, @Param("feedback") String feedback);
}
