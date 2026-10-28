# AgentRuntimeContext 运行中传递遇到的问题

## Hook 中获取失败

### 问题复现

Agent 运行过程中，请求线程与 Hook 执行线程不是同一个线程：

- 请求线程：`nio-8080-exec-2`
- Hook 线程：`boundedElastic-3`

请求线程中的日志如下：

![Agent 请求线程日志](images/agent-runtime-context-request-thread.png)

Hook 执行线程中的日志如下：

![Hook 执行线程日志](images/agent-runtime-context-hook-thread.png)

### 问题原因

`AgentRuntimeContextHolder` 使用 `ThreadLocal<AgentRuntimeContext>` 保存当前线程的上下文。`ThreadLocal` 的值绑定在线程对象上，只能被设置值的线程读取。

请求处理开始时，上下文绑定在 `nio-8080-exec-2` 线程中；AgentScope 执行 Hook 时切换到了 `boundedElastic-3` 线程。由于两个线程各自维护独立的 ThreadLocal 存储，所以 Hook 中调用 `AgentRuntimeContextHolder.get()` 时无法读取请求线程设置的上下文，结果为 `null`。

### 解决方案

在启动 Agent 对话时，通过 AgentScope 提供的 `Context.of(key, value)` 将 `AgentRuntimeContext` 放入本次 Agent 执行上下文：

```java
Context.of("agentRuntimeContext", agentRuntimeContext)
```

将该 `Context` 作为 Agent 运行方法的上下文参数传入后，AgentScope 会在执行 Hook 时提供这个上下文。Hook 从参数 `contextView` 中按相同的 key 读取：

```java
AgentRuntimeContext runtimeContext =
        contextView.getOrDefault("agentRuntimeContext", null);
```

因此，即使请求处理与 Hook 分别运行在 `nio-8080-exec-2` 和 `boundedElastic-3` 线程，也能在 Hook 中取得本次 Agent 调用对应的 `AgentRuntimeContext`。当前调用代码已在 `ChatAgentPipeline` 中使用 `Context.of("agentRuntimeContext", agentRuntimeContext)`，Hook 通过 `contextView.getOrDefault(...)` 读取该值。

### 结论

问题不是 `AgentRuntimeContext` 对象本身丢失，而是普通 `ThreadLocal` 不会随着异步线程切换自动传播。将上下文通过 `Context.of("agentRuntimeContext", agentRuntimeContext)` 显式传入 Agent 执行，并在 Hook 中从 `contextView` 读取，即可解决跨线程获取上下文为空的问题。读取时的 key 必须与创建 `Context` 时一致；`AgentRuntimeContextHolder` 仍只适用于当前线程内的访问，不应作为这条异步调用链的传播机制。
