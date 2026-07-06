# 诊断页安全策略审计入口缺失修复报告

## 问题

后端 `POST /api/diagnostics/security-audit` 已支持 `action=policy`，前端 API 也已有 `auditSecurity()` 封装，但诊断页安全审计下拉只提供命令、URL、路径、工具参数和策略状态，用户无法从 UI 运行完整策略审计。

## 影响

用户只能查看局部安全策略或手动调用接口，无法在 Dashboard 中确认完整安全覆盖面，降低了诊断页对后端安全审计能力的可发现性。

## 根因

`DiagnosticsView.vue` 的 `auditActionOptions` 没有包含 `policy`，中英文 locale 也缺少对应标签和说明。现有 `runAudit()` 已经能透传任意审计动作，因此不需要新增 API 或后端逻辑。

## 修复

在诊断页安全审计下拉中加入“完整策略 / Full policy”，并展示只读说明。提交前端静态回归测试，确保后端安全审计接口、UI 动作和中英文文案都存在。

## 验证

验证命令：

```powershell
cd web
node --experimental-strip-types tests/diagnosticsSecurityAuditUiStatic.test.ts
```
