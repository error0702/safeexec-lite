# Agent 上线前必须过的五道门（附 Spring Boot 实现）

> 一个能调工具的 Agent，从 Demo 到生产之间隔着五个问题。这篇文章每个问题给一段能跑的代码和一个故障注入测试。全部代码在 GitHub 开源版里能找到，文末有链接。

我做电商后端很多年。今年把一个采购 Agent 接进真实订单系统的时候，第一周就撞上了下面这五件事。它们和模型聪不聪明没关系，和"调用外部系统"这件事本身有关。

## 第一道门：请求超时了，你是不是买了两次？

Agent 调 `createPurchase`，供应商接口 30 秒没返回。SDK 抛了 `SocketTimeoutException`。大多数代码接下来会做的事是：重试。

问题是超时有三种可能：

- 请求没到供应商，重发安全
- 请求到了，供应商处理完了，回包丢了，重发就是第二单
- 请求到了，供应商还在处理，重发可能是第二单

三种情况在客户端看起来一模一样。所以第一条原则：

```text
UNKNOWN ≠ FAILED
```

超时之后 Attempt 的状态是 `UNKNOWN`，不是 `FAILED`。`UNKNOWN` 是"可能已经产生副作用"，`FAILED` 是"确定没有"。只有后者允许重发。

```java
} catch (DefinitiveFailureException e) {
    // 供应商明确拒绝（422 之类），确定没有副作用
    intents.markDefinitiveFailed(attempt.getId(), e.getMessage());
} catch (RuntimeException e) {
    // 发出去之后的任何异常：副作用可能存在
    intents.markUnknown(attempt.getId(), rootMessage(e));
}
```

那 `UNKNOWN` 之后怎么办？去问供应商。但问的结果也是三态，不是两态：

```java
public sealed interface RecoveryResult<O> {
    record Applied<O>(O result) implements RecoveryResult<O> {}
    record ConfirmedNotApplied<O>() implements RecoveryResult<O> {}
    record Indeterminate<O>(String reason) implements RecoveryResult<O> {}
}
```

供应商的查询接口返回"没找到"，不等于"没执行"。可能是查询侧有延迟。所以：

```text
NOT_FOUND ≠ CONFIRMED_NOT_EXECUTED
```

只有 `ConfirmedNotApplied` 允许重发，`Indeterminate` 转人工，绝不自动重试。

对应的测试：

```java
@DisplayName("Timeout after send: attempt UNKNOWN, not FAILED; the gateway never retries on its own")
void timeoutIsUnknownNotFailed() {
    supplier.setMode(ChaosSupplierClient.Mode.TIMEOUT_APPLIED);
    ToolResult r = gateway.invoke(purchase("o-1", 250), agent);
    assertThat(r).isInstanceOf(ToolResult.Unknown.class);
    assertThat(supplier.requestsReceived()).hasSize(1);
}
```

## 第二道门：用户批准了 300，Agent 改成了 3000

审批流程通常长这样：Agent 提出动作，人看一眼，点批准，Agent 执行。问题在"点批准"和"执行"之间的那几秒。Agent 可能重新规划了，参数变了，而审批记录还在。

解法是让被批准的东西不可变。我们把"一件业务事情"建模为 Intent，Intent 一旦开始执行，参数就冻结。改参数不是修改，是新建一个 Intent，需要新的审批。

这条规则不能只在 Java 里写，要在数据库里写：

```sql
CREATE TRIGGER trg_intent_immutable
    BEFORE UPDATE ON intent
    FOR EACH ROW EXECUTE FUNCTION safeexec_intent_immutable();
```

`safeexec_intent_immutable` 在 `status <> 'OPEN'` 时拒绝任何对 `params_json` 的修改。测试直接用 SQL 绕过所有 Java 代码去改，数据库报错才算通过：

```java
@DisplayName("Even a direct SQL UPDATE of params on a non-OPEN intent is rejected by the database trigger")
void databaseRejectsParamChangeOnceExecuting() {
    service.reserveAttempt(intent.getId());
    assertThatThrownBy(() -> jdbc.update("update intent set params_json = '{\"total\":3000}' where id = ?", intent.getId()))
            .hasMessageContaining("immutable");
}
```

参数不变还不够。批准时供应商报价 280 美元，两小时后执行，报价数据已经过期；批准时策略允许 500 以下，下午管理员改成 300 以上禁止。这两种情况下参数的哈希都没变，但都不该执行。所以第三条原则：

```text
APPROVED ≠ STILL_SAFE_TO_EXECUTE
```

执行前要重新检查依据数据的有效期，重新跑一遍当前策略。审批只是六步门禁中的一步。

## 第三道门：供应商处理了，你的进程在保存结果前崩了

很多审计日志是这样写的：执行完，写一条日志。如果执行完、写日志前 JVM 被 OOM kill 了，你恰好丢掉最需要的那条记录。

审计不是执行后的日志，是事件流。每个阶段追加一条事件，各自独立提交：

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void append(AuditEvent event) {
    int next = repository.maxSequence(event.traceId()) + 1;
    repository.saveAndFlush(AuditEventEntity.from(event, next));
}
```

关键是 `DISPATCHING` 这条事件在调外部系统之前就已经提交了。进程崩溃后重启，一个定时扫描器发现"DISPATCHING 状态超过 60 秒没更新"的 Attempt，把它转成 `UNKNOWN`，进入第一道门的恢复流程。

审计表还要拒绝修改。不是"我们没写 update 方法"，是数据库拒绝：

```sql
CREATE TRIGGER trg_audit_no_update
    BEFORE UPDATE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION safeexec_audit_immutable();
```

## 第四道门：Agent 在 staging 通过了测试，你敢让它写生产吗？

不敢。所以需要一个中间态：Agent 在真实数据上做真实判断，但不执行。所有 WRITE 和 IRREVERSIBLE 的工具调用被拦截，记录"本应执行什么"。人照常处理业务，事后对比 Agent 的建议和人的决定。

```java
if (shadow.blocks(tool, facts)) {
    audit.append(event(SHADOW_BLOCKED).resultDetail("would_have_executed=true").build());
    return new ToolResult.ShadowBlocked(eventId);
}
```

一致率达到门槛，再逐类放开。采购放开了退款可能还没放开。

还有一个容易漏的点：策略引擎和 Kill Switch 绝不能是 Agent 能调用的工具。注册一个叫 `toggleKillSwitch` 的工具应该让应用启动失败：

```java
@Test
void killSwitchIsNeverATool() {
    assertThatThrownBy(() -> new ToolRegistry().register(named("toggleKillSwitch")))
            .isInstanceOf(ToolRegistrationException.class);
}
```

同样重要的是安全组件自己坏了怎么办。策略文件读不出来，正确行为是拒绝所有写操作，而不是"没有策略等于全部允许"：

```java
@DisplayName("Fail closed: a corrupted policy file denies WRITE with POLICY_UNAVAILABLE while READ still works")
```

## 第五道门：三天前出了问题，你能还原当时发生了什么吗？

如果前四道门都过了，这一道是自然的结果。每次工具调用有一个 trace，trace 下按顺序是 `REQUEST_RECEIVED`、`VALIDATED`、`POLICY_DECIDED`、`ATTEMPT_CREATED`、`DISPATCHING`、`SUCCEEDED`。每条事件记录了策略版本、命中的规则、Intent 和 Attempt 的 ID、外部幂等键、输入的哈希和脱敏后的内容。

输入脱敏要递归。`{"supplier": {"credentials": {"token": "xxx"}}}` 里的 token 也要打码，不能只看第一层。

## 这五道门的共同点

它们都不是模型问题。换更强的模型，超时还是超时，进程还是会崩。它们是"让一个会主动行动的程序进入真实系统"的基础设施问题，和十年前接支付网关时要解决的问题是同一类。

区别是 Agent 的调用是模型生成的，参数可能不合法，动作可能越权，频率可能失控。所以每道门前面还有一道 Schema 校验和策略检查，而且策略只看"金额、币种、分组、环境"这类通用事实，不看业务 JSON。

## 代码

上面的代码来自 SafeExec for Spring Boot。开源版（MIT）包含 Tool Gateway、Schema 校验和审计事件流：

https://github.com/autorun/safeexec

完整版包含策略引擎、Intent 和 Attempt 幂等、三态恢复、审批门禁、崩溃恢复扫描器、影子模式，以及上面提到的全部故障注入测试。9 月 21 日交付，预售 49 美元：

https://autorun.fun/safeexec

Java 21，Spring Boot 3.5，PostgreSQL 16。不依赖任何模型厂商，Spring AI 只在适配层。没有遥测，没有许可证服务器，全部源码。

---

*如果你的 Agent 已经在生产环境里调工具了，我很想知道你们怎么处理超时。评论区或者 security@autorun.fun。*
