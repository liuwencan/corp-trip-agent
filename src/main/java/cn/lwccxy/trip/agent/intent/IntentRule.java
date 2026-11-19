package cn.lwccxy.trip.agent.intent;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** 一条本地意图规则，正向表达式命中且当前子句未命中任何排除表达式时生效。 */
public final class IntentRule {

    /** 正向关键词或正则，保留原文用于解释命中来源。 */
    @Getter
    private final String positiveRegex;

    /** 仅约束本条规则的排除表达式，不影响同意图的其他规则或其他子句。 */
    @Getter
    private final List<String> exclusionRegexes;

    private final Pattern positivePattern;
    private final List<Pattern> exclusionPatterns;

    /**
     * 创建并预编译规则，使配置错误在初始化时暴露，避免识别期间重复编译。
     *
     * @param positiveRegex 正向关键词或正则表达式
     * @param exclusionRegexes 任意一项命中即可否决本条规则的排除表达式
     */
    public IntentRule(String positiveRegex, String... exclusionRegexes) {
        this.positiveRegex = positiveRegex;
        this.exclusionRegexes = List.of(exclusionRegexes);
        // Pattern 可跨请求复用；每次匹配单独创建 Matcher，避免共享可变匹配状态。
        this.positivePattern = Pattern.compile(positiveRegex);
        this.exclusionPatterns = Arrays.stream(exclusionRegexes).map(Pattern::compile).toList();
    }

    /**
     * 在当前子句内检查正向条件与排除条件。
     *
     * @param clause 已完成拆分和空白清理的子句
     * @return 正向命中且所有排除条件均未命中时返回 true
     */
    public boolean matches(String clause) {
        // 排除只作用于当前规则，不能因否定取消操作而一并屏蔽订单查询规则。
        return clause != null && !clause.isBlank()
                && positivePattern.matcher(clause).find()
                && exclusionPatterns.stream().noneMatch(pattern -> pattern.matcher(clause).find());
    }
}
