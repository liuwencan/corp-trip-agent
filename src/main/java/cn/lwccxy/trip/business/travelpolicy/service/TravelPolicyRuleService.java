package cn.lwccxy.trip.business.travelpolicy.service;

import cn.lwccxy.trip.business.travelpolicy.domain.entity.TravelPolicyRuleEntity;
import cn.lwccxy.trip.business.travelpolicy.domain.form.TravelPolicyRuleQueryForm;
import cn.lwccxy.trip.business.travelpolicy.domain.form.TravelPolicyRuleSaveForm;
import cn.lwccxy.trip.business.travelpolicy.domain.vo.TravelPolicyRuleVO;
import cn.lwccxy.trip.business.travelpolicy.mapper.TravelPolicyRuleMapper;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提供差旅政策规则的分页查询和 CRUD 能力。 */
@Service
@RequiredArgsConstructor
public class TravelPolicyRuleService {

    private final TravelPolicyRuleMapper mapper;

    /** 按城市等级或职级区间分页查询差旅政策规则。 */
    public PageResponse<TravelPolicyRuleVO> query(TravelPolicyRuleQueryForm form) {
        // 让数据库执行筛选和分页，避免将全部政策规则载入应用内存。
        LambdaQueryWrapper<TravelPolicyRuleEntity> query = new LambdaQueryWrapper<>();
        if (form.getCityTier() != null && !form.getCityTier().isBlank()) {
            query.eq(TravelPolicyRuleEntity::getCityTier, form.getCityTier().strip());
        }
        if (form.getLevel() != null) {
            query.le(TravelPolicyRuleEntity::getLevelMin, form.getLevel())
                    .ge(TravelPolicyRuleEntity::getLevelMax, form.getLevel());
        }
        query.orderByAsc(TravelPolicyRuleEntity::getLevelMin)
                .orderByAsc(TravelPolicyRuleEntity::getCityTier)
                .orderByAsc(TravelPolicyRuleEntity::getId);

        Page<TravelPolicyRuleEntity> page = mapper.selectPage(
                new Page<>(form.getPageNo(), form.getPageSize()), query);
        return PageResponse.success(page.getRecords().stream().map(this::toVO).toList(),
                page.getCurrent(), page.getTotal(), page.getSize());
    }

    /** 根据主键查询差旅政策规则。 */
    public Response<TravelPolicyRuleVO> get(Long id) {
        TravelPolicyRuleEntity entity = mapper.selectById(id);
        return entity == null
                ? Response.fail("TRAVEL_POLICY_RULE_NOT_FOUND", "差旅政策规则不存在")
                : Response.success(toVO(entity));
    }

    /** 新增差旅政策规则，并将唯一索引冲突转换为业务响应。 */
    @Transactional
    public Response<TravelPolicyRuleVO> create(TravelPolicyRuleSaveForm form) {
        if (form.getLevelMin() > form.getLevelMax()) {
            return Response.fail("INVALID_TRAVEL_POLICY_LEVEL_RANGE", "职级区间下限不能大于上限");
        }
        TravelPolicyRuleEntity entity = toEntity(form);
        if (existsDuplicate(entity, null)) {
            return Response.fail("TRAVEL_POLICY_RULE_ALREADY_EXISTS", "该职级区间和城市等级的规则已存在");
        }

        try {
            // 唯一索引负责处理并发新增时的最终冲突，保证接口不会返回数据库异常。
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            return Response.fail("TRAVEL_POLICY_RULE_ALREADY_EXISTS", "该职级区间和城市等级的规则已存在");
        }
        return Response.success(toVO(entity));
    }

    /** 更新差旅政策规则，并保留数据库生成的主键。 */
    @Transactional
    public Response<TravelPolicyRuleVO> update(Long id, TravelPolicyRuleSaveForm form) {
        TravelPolicyRuleEntity old = mapper.selectById(id);
        if (old == null) {
            return Response.fail("TRAVEL_POLICY_RULE_NOT_FOUND", "差旅政策规则不存在");
        }
        if (form.getLevelMin() > form.getLevelMax()) {
            return Response.fail("INVALID_TRAVEL_POLICY_LEVEL_RANGE", "职级区间下限不能大于上限");
        }

        TravelPolicyRuleEntity entity = toEntity(form);
        if (existsDuplicate(entity, id)) {
            return Response.fail("TRAVEL_POLICY_RULE_ALREADY_EXISTS", "该职级区间和城市等级的规则已存在");
        }
        // 更新只接受表单中的业务字段，ID 始终取路径参数，防止请求体替换目标记录。
        entity.setId(id);
        try {
            mapper.updateById(entity);
        } catch (DuplicateKeyException exception) {
            return Response.fail("TRAVEL_POLICY_RULE_ALREADY_EXISTS", "该职级区间和城市等级的规则已存在");
        }
        return Response.success(toVO(mapper.selectById(id)));
    }

    /** 删除差旅政策规则。 */
    @Transactional
    public Response<Void> delete(Long id) {
        int rows = mapper.deleteById(id);
        return rows == 0
                ? Response.fail("TRAVEL_POLICY_RULE_NOT_FOUND", "差旅政策规则不存在")
                : Response.success();
    }

    /** 检查职级区间和城市等级组合是否已被其他记录占用。 */
    private boolean existsDuplicate(TravelPolicyRuleEntity entity, Long excludeId) {
        LambdaQueryWrapper<TravelPolicyRuleEntity> query = new LambdaQueryWrapper<TravelPolicyRuleEntity>()
                .eq(TravelPolicyRuleEntity::getLevelMin, entity.getLevelMin())
                .eq(TravelPolicyRuleEntity::getLevelMax, entity.getLevelMax())
                .eq(TravelPolicyRuleEntity::getCityTier, entity.getCityTier());
        if (excludeId != null) {
            query.ne(TravelPolicyRuleEntity::getId, excludeId);
        }
        return mapper.selectCount(query) > 0;
    }

    /** 将保存请求转换为待持久化实体，并规范化可选文本。 */
    private TravelPolicyRuleEntity toEntity(TravelPolicyRuleSaveForm form) {
        TravelPolicyRuleEntity entity = new TravelPolicyRuleEntity();
        BeanUtils.copyProperties(form, entity);
        entity.setCityTier(form.getCityTier().strip());
        entity.setFlightClass(normalize(form.getFlightClass()));
        entity.setTrainSeatClass(normalize(form.getTrainSeatClass()));
        return entity;
    }

    /** 将数据库实体转换为接口返回对象，避免直接暴露持久层类型。 */
    private TravelPolicyRuleVO toVO(TravelPolicyRuleEntity entity) {
        TravelPolicyRuleVO vo = new TravelPolicyRuleVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /** 将空白可选文本统一保存为 null。 */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
