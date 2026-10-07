package cn.lwccxy.trip.system.config.service;

import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.system.config.domain.entity.ConfigEntity;
import cn.lwccxy.trip.system.config.domain.form.ConfigQueryForm;
import cn.lwccxy.trip.system.config.domain.form.ConfigSaveForm;
import cn.lwccxy.trip.system.config.domain.form.ConfigValueUpdateForm;
import cn.lwccxy.trip.system.config.domain.vo.ConfigVO;
import cn.lwccxy.trip.system.config.enums.ConfigKeyEnum;
import cn.lwccxy.trip.system.config.mapper.ConfigMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ConfigService extends ServiceImpl<ConfigMapper, ConfigEntity> {

    private final Map<String, ConfigEntity> configCache = new ConcurrentHashMap<>();

    @Resource
    private ConfigMapper configMapper;

    @PostConstruct
    public void loadConfigCache() {
        configCache.clear();
        List<ConfigEntity> entityList = configMapper.selectList(null);
        entityList.forEach(entity -> configCache.put(normalizeKey(entity.getConfigKey()), entity));
        log.info("Loaded {} dynamic configurations", configCache.size());
    }

    public int reload() {
        loadConfigCache();
        return configCache.size();
    }

    public ConfigVO getConfig(ConfigKeyEnum configKey) {
        return getConfig(configKey.getValue());
    }

    public ConfigVO getConfig(String configKey) {
        if (configKey == null || configKey.isBlank()) {
            return null;
        }
        return toVO(configCache.get(normalizeKey(configKey)));
    }

    public String getConfigValue(ConfigKeyEnum configKey) {
        return getConfigValue(configKey.getValue());
    }

    public String getConfigValue(String configKey) {
        ConfigEntity config = cached(configKey);
        return config == null ? null : config.getConfigValue();
    }

    public boolean getBoolean(String configKey, boolean defaultValue) {
        ConfigEntity config = cached(configKey);
        return config == null ? defaultValue : Boolean.parseBoolean(config.getConfigValue());
    }

    public boolean getBoolean(ConfigKeyEnum configKey, boolean defaultValue) {
        return getBoolean(configKey.getValue(), defaultValue);
    }

    public int getInt(String configKey, int defaultValue) {
        ConfigEntity config = cached(configKey);
        return config == null ? defaultValue : Integer.parseInt(config.getConfigValue());
    }

    public int getInt(ConfigKeyEnum configKey, int defaultValue) {
        return getInt(configKey.getValue(), defaultValue);
    }

    public long getLong(String configKey, long defaultValue) {
        ConfigEntity config = cached(configKey);
        return config == null ? defaultValue : Long.parseLong(config.getConfigValue());
    }

    public long getLong(ConfigKeyEnum configKey, long defaultValue) {
        return getLong(configKey.getValue(), defaultValue);
    }

    public BigDecimal getDecimal(String configKey, BigDecimal defaultValue) {
        ConfigEntity config = cached(configKey);
        return config == null ? defaultValue : new BigDecimal(config.getConfigValue());
    }

    public <T> T getJson(String configKey, Class<T> clazz) {
        ConfigEntity config = cached(configKey);
        return config == null ? null : JSON.parseObject(config.getConfigValue(), clazz);
    }

    public <T> T getConfigValue2Obj(ConfigKeyEnum configKey, Class<T> clazz) {
        return getJson(configKey.getValue(), clazz);
    }

    public <T> T getConfigValue2Obj(String configKey, Class<T> clazz) {
        return getJson(configKey, clazz);
    }

    public PageResponse<ConfigVO> query(ConfigQueryForm form) {
        Page<ConfigEntity> page = new Page<>(form.getPageNo(), form.getPageSize());
        LambdaQueryWrapper<ConfigEntity> query = new LambdaQueryWrapper<>();
        if (form.getKeyword() != null && !form.getKeyword().isBlank()) {
            String keyword = form.getKeyword().trim();
            query.like(ConfigEntity::getConfigKey, keyword)
                    .or()
                    .like(ConfigEntity::getConfigName, keyword);
        }
        query.orderByDesc(ConfigEntity::getUpdateTime)
                .orderByDesc(ConfigEntity::getId);
        Page<ConfigEntity> result = configMapper.selectPage(page, query);
        List<ConfigVO> data = result.getRecords().stream().map(this::toVO).toList();
        return PageResponse.success(data, result.getCurrent(), result.getTotal(), result.getSize());
    }

    public Response<ConfigVO> add(ConfigSaveForm form) {
        String key = normalizeKey(form.getConfigKey());
        if (findEntity(key) != null) {
            return Response.fail("CONFIG_ALREADY_EXISTS", "Config key already exists");
        }
        ConfigEntity entity;
        try {
            entity = toEntity(form, key);
        } catch (IllegalArgumentException ex) {
            return Response.fail("INVALID_CONFIG_VALUE", ex.getMessage());
        }
        configMapper.insert(entity);
        configCache.put(key, entity);
        return Response.success(toVO(entity));
    }

    public Response<ConfigVO> update(Long id, ConfigSaveForm form) {
        ConfigEntity old = configMapper.selectById(id);
        if (old == null) {
            return Response.fail("CONFIG_NOT_FOUND", "Config does not exist");
        }
        String key = normalizeKey(form.getConfigKey());
        ConfigEntity sameKey = findEntity(key);
        if (sameKey != null && !id.equals(sameKey.getId())) {
            return Response.fail("CONFIG_ALREADY_EXISTS", "Config key already exists");
        }
        ConfigEntity entity;
        try {
            entity = toEntity(form, key);
        } catch (IllegalArgumentException ex) {
            return Response.fail("INVALID_CONFIG_VALUE", ex.getMessage());
        }
        entity.setId(id);
        entity.setCreateTime(old.getCreateTime());
        configMapper.updateById(entity);
        configCache.remove(normalizeKey(old.getConfigKey()));
        configCache.put(key, entity);
        return Response.success(toVO(entity));
    }

    public Response<ConfigVO> updateValue(ConfigValueUpdateForm form) {
        String key = normalizeKey(form.getConfigKey());
        ConfigEntity entity = findEntity(key);
        if (entity == null) {
            return Response.fail("CONFIG_NOT_FOUND", "Config does not exist");
        }
        entity.setConfigValue(form.getConfigValue());
        configMapper.updateById(entity);
        configCache.put(key, entity);
        return Response.success(toVO(entity));
    }

    public Response<Void> delete(Long id) {
        ConfigEntity entity = configMapper.selectById(id);
        if (entity == null) {
            return Response.fail("CONFIG_NOT_FOUND", "Config does not exist");
        }
        configMapper.deleteById(id);
        configCache.remove(normalizeKey(entity.getConfigKey()));
        return Response.success();
    }

    private ConfigEntity toEntity(ConfigSaveForm form, String key) {
        ConfigEntity entity = new ConfigEntity();
        entity.setConfigKey(key);
        entity.setConfigName(form.getConfigName() == null || form.getConfigName().isBlank()
                ? key : form.getConfigName().trim());
        entity.setConfigValue(form.getConfigValue());
        entity.setRemark(form.getRemark());
        return entity;
    }

    private ConfigEntity cached(String configKey) {
        return configKey == null ? null : configCache.get(normalizeKey(configKey));
    }

    private ConfigEntity findEntity(String configKey) {
        ConfigEntity cached = configCache.get(configKey);
        if (cached != null) {
            return cached;
        }
        return configMapper.selectOne(new LambdaQueryWrapper<ConfigEntity>()
                .eq(ConfigEntity::getConfigKey, configKey));
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private ConfigVO toVO(ConfigEntity entity) {
        if (entity == null) {
            return null;
        }
        ConfigVO vo = new ConfigVO();
        vo.setId(entity.getId());
        vo.setConfigKey(entity.getConfigKey());
        vo.setConfigValue(entity.getConfigValue());
        vo.setConfigName(entity.getConfigName());
        vo.setRemark(entity.getRemark());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
