package cn.lwccxy.trip.system.prompt.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.system.prompt.domain.form.PromptOperationForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptQueryForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptRollbackForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptSaveForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptVersionSaveForm;
import cn.lwccxy.trip.system.prompt.domain.vo.PromptReleaseLogVO;
import cn.lwccxy.trip.system.prompt.domain.vo.PromptVersionVO;
import cn.lwccxy.trip.system.prompt.domain.vo.PromptVersionCompareVO;
import cn.lwccxy.trip.system.prompt.domain.vo.PromptVO;
import cn.lwccxy.trip.system.prompt.service.PromptService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@SaCheckRole("ADMIN")
@RequestMapping("/system/prompt")
public class PromptController {

    @Resource
    private PromptService promptService;

    @PostMapping("/query")
    public PageResponse<PromptVO> query(@RequestBody @Valid PromptQueryForm form) {
        return promptService.query(form);
    }

    @GetMapping("/{id}")
    public Response<PromptVO> get(@PathVariable Long id) {
        return promptService.get(id);
    }

    @PostMapping
    public Response<PromptVO> add(@RequestBody @Valid PromptSaveForm form) {
        return promptService.add(form);
    }

    @PostMapping("/{promptId}/versions")
    public Response<PromptVersionVO> addVersion(@PathVariable Long promptId,
                                                @RequestBody @Valid PromptVersionSaveForm form) {
        return promptService.addVersion(promptId, form);
    }

    @GetMapping("/{promptId}/versions")
    public PageResponse<PromptVersionVO> listVersions(@PathVariable Long promptId,
                                                      @RequestParam(defaultValue = "1") @Min(1) long pageNo,
                                                      @RequestParam(defaultValue = "10") @Min(1) @Max(200) long pageSize) {
        return promptService.listVersions(promptId, pageNo, pageSize);
    }

    @GetMapping("/{promptId}/versions/{versionId}")
    public Response<PromptVersionVO> getVersion(@PathVariable Long promptId,
                                                @PathVariable Long versionId) {
        return promptService.getVersion(promptId, versionId);
    }

    @GetMapping("/{promptId}/versions/compare")
    public Response<PromptVersionCompareVO> compareVersions(@PathVariable Long promptId,
                                                            @RequestParam Long leftVersionId,
                                                            @RequestParam Long rightVersionId) {
        return promptService.compareVersions(promptId, leftVersionId, rightVersionId);
    }

    @PostMapping("/{promptId}/versions/{versionId}/publish")
    public Response<PromptVersionVO> publish(@PathVariable Long promptId,
                                             @PathVariable Long versionId,
                                             @RequestBody @Valid PromptOperationForm form) {
        return promptService.publish(promptId, versionId, form);
    }

    @PostMapping("/{promptId}/offline")
    public Response<Void> offline(@PathVariable Long promptId,
                                  @RequestBody @Valid PromptOperationForm form) {
        return promptService.offline(promptId, form);
    }

    @PostMapping("/{promptId}/rollback")
    public Response<PromptVersionVO> rollback(@PathVariable Long promptId,
                                              @RequestBody @Valid PromptRollbackForm form) {
        return promptService.rollback(promptId, form);
    }

    @GetMapping("/{promptId}/release-logs")
    public Response<List<PromptReleaseLogVO>> listReleaseLogs(@PathVariable Long promptId) {
        return Response.success(promptService.listReleaseLogs(promptId));
    }
}
