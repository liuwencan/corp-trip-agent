package cn.lwccxy.trip.business.trip.domain.form;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** 出差业务请求参数基类，用于拒绝未定义的 JSON 字段。 */
public abstract class TripRequestForm {

    /** 拒绝请求中的未知字段，避免客户端误传参数被静默忽略。 */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unsupported request field: " + name);
    }
}
