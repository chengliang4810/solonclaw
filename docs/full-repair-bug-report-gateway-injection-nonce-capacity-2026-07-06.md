# 网关注入 nonce 满容量时淘汰有效 nonce 问题修复报告

## 问题现象

外部消息网关注入认证会缓存已使用 nonce 来阻止重放请求。缓存达到上限后，旧逻辑会继续接受新 nonce，并在后续请求中淘汰仍处于重放窗口内的旧 nonce。攻击者或异常客户端可以通过大量不同 nonce 挤掉有效窗口内的旧记录，削弱重放检测。

## 影响范围

- 影响外部消息网关注入接口的 HMAC 防重放保护。
- 只影响 nonce 缓存达到上限后的边界行为。
- 不影响正常容量内的首次请求和重复 nonce 拦截。

## 根因

`GatewayInjectionAuthService.markNonce()` 在同一个循环中同时处理过期清理和容量淘汰：

```java
if (now - entry.getValue() > window || seenNonces.size() > MAX_NONCES) {
    iterator.remove();
}
```

容量超限时，该条件会删除仍未过期的旧 nonce。删除后同一 nonce 在重放窗口内再次出现时，服务端无法再识别它已被使用。

## 修复方案

将过期清理和容量控制拆开：

- 循环中只删除已经超过重放窗口的 nonce。
- 清理后如果 nonce 已存在，继续拒绝。
- 如果缓存仍达到上限，拒绝新 nonce，不淘汰窗口内旧 nonce。

该策略按安全边界 fail-closed，避免为了接收新请求牺牲已有重放检测。

## 回归测试

新增 `GatewayInjectionAuthServiceTest.shouldRejectNewNonceWhenLiveNonceCacheIsFull`，覆盖：

- 构造满容量且全部未过期的 nonce 缓存。
- 新 nonce 被拒绝。
- 已存在的旧 nonce 仍保留，并继续被识别为重复。

验证命令：

```powershell
mvn "-Dskip.web.build=true" "-Dtest=GatewayInjectionAuthServiceTest" test
```

结果：测试先失败确认缺陷存在，修复后通过。
