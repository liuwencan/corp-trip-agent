package cn.lwccxy.trip.system.config.controller;

import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.system.config.domain.form.ConfigQueryForm;
import cn.lwccxy.trip.system.config.domain.form.ConfigSaveForm;
import cn.lwccxy.trip.system.config.domain.form.ConfigValueUpdateForm;
import cn.lwccxy.trip.system.config.domain.vo.ConfigVO;
import cn.lwccxy.trip.system.config.service.ConfigService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/config")
public class AdminConfigController {

    @Resource
    private ConfigService configService;

    @PostMapping("/query")
    public PageResponse<ConfigVO> query(@RequestBody @Valid ConfigQueryForm form) {
        return configService.query(form);
    }

    @GetMapping("/{configKey}")
    public Response<ConfigVO> get(@PathVariable String configKey) {
        ConfigVO config = configService.getConfig(configKey);
        return config == null
                ? Response.fail("CONFIG_NOT_FOUND", "Config does not exist")
                : Response.success(config);
    }

    @PostMapping
    public Response<ConfigVO> add(@RequestBody @Valid ConfigSaveForm form) {
        return configService.add(form);
    }

    @PutMapping("/{id}")
    public Response<ConfigVO> update(@PathVariable Long id,
                                     @RequestBody @Valid ConfigSaveForm form) {
        return configService.update(id, form);
    }

    @PutMapping("/value")
    public Response<ConfigVO> updateValue(@RequestBody @Valid ConfigValueUpdateForm form) {
        return configService.updateValue(form);
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        return configService.delete(id);
    }

    @PostMapping("/reload")
    public Response<Integer> reload() {
        return Response.success(configService.reload());
    }
}
