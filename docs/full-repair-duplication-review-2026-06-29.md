# 全面修复阶段 1.4 代码重复检测与复用优化记录

生成时间：2026-06-29

## 对应外部对标能力点

- 代码质量治理：减少重复测试支撑、重复工具断言和重复目录夹具，降低安全策略测试维护成本。
- 工具系统安全边界：危险命令审批、文件策略、网络策略和网关审批测试需要共享同一套断言语义，避免各测试类各自维护轻微分叉的判断。

## 本轮处理结论

本轮完成一组低风险、高确定性的重复代码复用改造：

- 已把五个危险命令测试类重复维护的 `approvalEnvironment()`、`assertToolSuccess()`、`assertToolError()`、`workspaceBoundaryParent()`、`workspaceBoundaryWorkspace()` 迁入已有 `DangerousCommandApprovalTestSupport`。
- 五个测试类保留各自的 `@AfterEach` 生命周期方法，但清理逻辑改为调用统一 helper，避免改动 JUnit 生命周期结构。
- 本次只改测试代码，不改生产逻辑。

提交：

- `6a4f1b5fa test: 复用危险命令测试辅助方法 / Reuse dangerous command test helpers`

## 已处理文件

- `src/test/java/com/jimuqu/solon/claw/DangerousCommandApprovalTestSupport.java`
- `src/test/java/com/jimuqu/solon/claw/DangerousCommandApprovalServiceTest.java`
- `src/test/java/com/jimuqu/solon/claw/DangerousCommandCodeAndNetworkPolicyTest.java`
- `src/test/java/com/jimuqu/solon/claw/DangerousCommandCredentialPolicyTest.java`
- `src/test/java/com/jimuqu/solon/claw/DangerousCommandFilePolicyTest.java`
- `src/test/java/com/jimuqu/solon/claw/DangerousCommandGatewayApprovalTest.java`

## 当前扫描结果

已执行：

```bash
python3 scripts/check-code-duplication.selftest.py
python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages
python3 scripts/check-code-duplication.py --report-only --min-lines 25 src/test/java/com/jimuqu/solon/claw/DangerousCommandApprovalServiceTest.java src/test/java/com/jimuqu/solon/claw/DangerousCommandCodeAndNetworkPolicyTest.java src/test/java/com/jimuqu/solon/claw/DangerousCommandCredentialPolicyTest.java src/test/java/com/jimuqu/solon/claw/DangerousCommandFilePolicyTest.java src/test/java/com/jimuqu/solon/claw/DangerousCommandGatewayApprovalTest.java
```

结果：

- 重复检测脚本自测通过。
- 全项目源码 `min-lines=40` 扫描无输出。
- 危险命令测试族 `min-lines=25` 扫描仍有一组重复块：`DangerousCommandCodeAndNetworkPolicyTest.java:1780` 与 `DangerousCommandGatewayApprovalTest.java:1798`。该重复属于两个不同测试叙事里的相似审批流程，继续抽取会让测试可读性下降，本轮保留。

## 已验证

```bash
mvn -Dskip.web.build=true -Dtest=DangerousCommandApprovalServiceTest,DangerousCommandCodeAndNetworkPolicyTest,DangerousCommandCredentialPolicyTest,DangerousCommandFilePolicyTest,DangerousCommandGatewayApprovalTest test
mvn -Dskip.web.build=true -DskipTests compile
git diff --check -- src/test/java/com/jimuqu/solon/claw/DangerousCommandApprovalTestSupport.java src/test/java/com/jimuqu/solon/claw/DangerousCommandApprovalServiceTest.java src/test/java/com/jimuqu/solon/claw/DangerousCommandCodeAndNetworkPolicyTest.java src/test/java/com/jimuqu/solon/claw/DangerousCommandCredentialPolicyTest.java src/test/java/com/jimuqu/solon/claw/DangerousCommandFilePolicyTest.java src/test/java/com/jimuqu/solon/claw/DangerousCommandGatewayApprovalTest.java
python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range
```

验证结果：

- 危险命令测试子集通过：257 个测试，0 失败，0 错误。
- Java 编译通过。
- diff whitespace 检查通过。
- 项目命名门禁通过。

## 2026-07-05 复核结果

已执行：

```bash
python scripts/check-code-duplication.selftest.py
python scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages
python scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages
```

结果：

- 重复检测脚本自测通过。
- 全项目源码 `min-lines=40` 扫描无输出。
- 全项目源码 `min-lines=25` 宽松扫描也无输出。
- 当前没有脚本可复现的重复代码块，因此本轮不做新的复用抽取。

## 保留的高相似候选

以下候选来自当前质量审计与本轮复核，后续应按更小的原子项串行处理：

| 候选 | 判断 | 建议 |
| --- | --- | --- |
| `web/src/components/solonclaw/models/CodexLoginModal.vue` 与 `web/src/components/solonclaw/models/NousLoginModal.vue` | 设备码登录流程高度相似。 | 后续抽一个 provider 可配置的设备码登录组件或 composable，并做 Web build 与页面交互验证。 |
| `web/src/views/solonclaw/MemoryView.vue` 与 `web/src/views/solonclaw/PersonaFileView.vue` | 工作区文件读取、编辑、保存流程相似。 | 后续在修改这两个页面时抽共享文件编辑面板，避免单独为复用引入视觉回归风险。 |
| `src/main/java/com/jimuqu/solon/claw/web/DomesticQrSetupService.java` 与 `src/main/java/com/jimuqu/solon/claw/web/WeixinQrSetupService.java` | ticket 生命周期和状态输出相似，但平台协议不同。 | 先抽 `TicketState` 或状态输出小 helper，不要把不同平台协议揉成一个大服务。 |
| `src/main/java/com/jimuqu/solon/claw/gateway/platform/qqbot/QQBotChannelAdapter.java` 与 `src/main/java/com/jimuqu/solon/claw/gateway/platform/yuanbao/YuanbaoChannelAdapter.java` | 国内渠道适配器结构相似，但鉴权、消息和媒体语义不同。 | 只在连续修改渠道连接或附件逻辑时抽小型 URL 安全校验、附件转换 helper。 |
| 多个测试类中的 `AllowLocalButBlockMetadataSecurityPolicyService` | 本地允许、元数据阻断的安全策略测试桩重复。 | 下一轮低风险复用候选，可抽到测试 support 包并验证相关 URL 安全测试。 |

## 阶段处理决定

- 阶段 1.4 已完成一组实际复用改造，并留下当前重复候选清单。
- 不在本阶段继续抽前端登录弹窗或渠道适配器，因为它们需要 UI 或平台协议级验证，应该作为后续独立原子项处理。
- 下一阶段进入前后端功能一致性修复前，优先把 BUG-008 纳入阶段 2.1 / 2.3。
