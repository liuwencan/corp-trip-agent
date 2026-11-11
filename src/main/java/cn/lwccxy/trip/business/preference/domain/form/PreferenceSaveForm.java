package cn.lwccxy.trip.business.preference.domain.form;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 用户长期记忆偏好保存请求。 */
@Data
public class PreferenceSaveForm {

    /** 按偏好字段编码组织的选项值。 */
    @NotNull
    @Size(max = 32)
    private Map<String, Object> preferences;

    /** 接收参考接口直接提交的字段对象，并统一归入偏好映射。 */
    @JsonAnySetter
    public void addPreference(String key, Object value) {
        if (preferences == null) {
            preferences = new LinkedHashMap<>();
        }
        preferences.put(key, value);
    }
}
