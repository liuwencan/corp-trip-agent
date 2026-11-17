package cn.lwccxy.trip.agent.store.plan;

import com.alibaba.fastjson2.JSON;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.PlanState;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.plan.model.SubTaskState;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 存储专用快照，显式包含 AgentScope 1.0.12 模型中标记为 JsonIgnore 的运行状态。
 * <p>不直接序列化 SDK 模型，防止其面向模型输入的忽略注解导致存档字段丢失。
 */
@Data
class PlanSnapshot {

    private String id;
    private String name;
    private String description;
    private String expectedOutcome;
    private List<TaskSnapshot> subtasks;
    private String createdAt;
    private PlanState state;
    private String finishedAt;
    private String outcome;

    /**
     * 将第三方计划转换为包含所有持久化字段的 JSON。
     *
     * @param plan 待保存计划
     * @return 独立的 JSON 快照
     */
    static String toJson(Plan plan) {
        PlanSnapshot snapshot = new PlanSnapshot();
        // 显式读取 getter，绕过 SDK 对 ID、状态和完成信息的 JsonIgnore 标记。
        snapshot.setId(plan.getId());
        snapshot.setName(plan.getName());
        snapshot.setDescription(plan.getDescription());
        snapshot.setExpectedOutcome(plan.getExpectedOutcome());
        snapshot.setSubtasks(plan.getSubtasks() == null ? null
                : plan.getSubtasks().stream().map(TaskSnapshot::fromTask).toList());
        snapshot.setCreatedAt(plan.getCreatedAt());
        snapshot.setState(plan.getState());
        snapshot.setFinishedAt(plan.getFinishedAt());
        snapshot.setOutcome(plan.getOutcome());
        return JSON.toJSONString(snapshot);
    }

    /**
     * 将 JSON 恢复为计划，覆盖 SDK 构造方法生成的新 ID 和时间等默认值。
     *
     * @param json 持久化快照
     * @return 原始计划状态
     */
    static Plan fromJson(String json) {
        PlanSnapshot snapshot = Objects.requireNonNull(
                JSON.parseObject(json, PlanSnapshot.class), "计划快照不能为 null");
        Plan plan = new Plan();
        // 所有字段都通过 setter 恢复，包括显式的 null，避免构造默认值污染历史状态。
        plan.setId(snapshot.getId());
        plan.setName(snapshot.getName());
        plan.setDescription(snapshot.getDescription());
        plan.setExpectedOutcome(snapshot.getExpectedOutcome());
        // 恢复后的列表必须可变，PlanNotebook 的修订工具会原地增删和替换子任务。
        plan.setSubtasks(snapshot.getSubtasks() == null ? null
                : snapshot.getSubtasks().stream().map(TaskSnapshot::toTask)
                        .collect(Collectors.toCollection(ArrayList::new)));
        plan.setCreatedAt(snapshot.getCreatedAt());
        plan.setState(snapshot.getState());
        plan.setFinishedAt(snapshot.getFinishedAt());
        plan.setOutcome(snapshot.getOutcome());
        return plan;
    }

    /** 保存子任务全部字段，尤其是 SDK 默认忽略的完成信息。 */
    @Data
    static class TaskSnapshot {

        private String name;
        private String description;
        private String expectedOutcome;
        private String outcome;
        private SubTaskState state;
        private String createdAt;
        private String finishedAt;

        /**
         * 提取子任务的完整状态，同时保留空元素。
         *
         * @param task 第三方子任务对象
         * @return 子任务快照
         */
        private static TaskSnapshot fromTask(SubTask task) {
            if (task == null) {
                return null;
            }
            // 子任务的实际结果和完成时间同样需要显式提取。
            TaskSnapshot snapshot = new TaskSnapshot();
            snapshot.setName(task.getName());
            snapshot.setDescription(task.getDescription());
            snapshot.setExpectedOutcome(task.getExpectedOutcome());
            snapshot.setOutcome(task.getOutcome());
            snapshot.setState(task.getState());
            snapshot.setCreatedAt(task.getCreatedAt());
            snapshot.setFinishedAt(task.getFinishedAt());
            return snapshot;
        }

        /**
         * 按快照重建子任务，避免 SDK 默认值改变保存时的状态。
         *
         * @param snapshot 子任务快照
         * @return 第三方子任务对象
         */
        private static SubTask toTask(TaskSnapshot snapshot) {
            if (snapshot == null) {
                return null;
            }
            // 全量恢复字段，保留待办、进行中、完成和放弃等各类状态。
            SubTask task = new SubTask();
            task.setName(snapshot.getName());
            task.setDescription(snapshot.getDescription());
            task.setExpectedOutcome(snapshot.getExpectedOutcome());
            task.setOutcome(snapshot.getOutcome());
            task.setState(snapshot.getState());
            task.setCreatedAt(snapshot.getCreatedAt());
            task.setFinishedAt(snapshot.getFinishedAt());
            return task;
        }
    }
}
