package cn.lwccxy.trip.business.travelpolicy.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.lwccxy.trip.business.travelpolicy.domain.form.TravelPolicyRuleQueryForm;
import cn.lwccxy.trip.business.travelpolicy.domain.form.TravelPolicyRuleSaveForm;
import cn.lwccxy.trip.business.travelpolicy.domain.vo.TravelPolicyRuleVO;
import cn.lwccxy.trip.business.travelpolicy.service.TravelPolicyRuleService;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员维护差旅政策规则的后台接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
@RequestMapping("/admin/travel-policy/rules")
public class AdminTravelPolicyRuleController {

    private final TravelPolicyRuleService service;

    /** 分页查询差旅政策规则。 */
    @GetMapping
    public PageResponse<TravelPolicyRuleVO> query(@Valid @ModelAttribute TravelPolicyRuleQueryForm form) {
        return service.query(form);
    }

    /** 根据主键查询差旅政策规则。 */
    @GetMapping("/{id}")
    public Response<TravelPolicyRuleVO> get(@Positive @PathVariable Long id) {
        return service.get(id);
    }

    /** 新增差旅政策规则。 */
    @PostMapping
    public Response<TravelPolicyRuleVO> create(@Valid @RequestBody TravelPolicyRuleSaveForm form) {
        return service.create(form);
    }

    /** 更新差旅政策规则。 */
    @PutMapping("/{id}")
    public Response<TravelPolicyRuleVO> update(@Positive @PathVariable Long id,
                                               @Valid @RequestBody TravelPolicyRuleSaveForm form) {
        return service.update(id, form);
    }

    /** 删除差旅政策规则。 */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@Positive @PathVariable Long id) {
        return service.delete(id);
    }
}
