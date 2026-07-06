# TUI quiet lint 被测试文件 import 顺序阻断

## 范围

- `terminal-ui` 的 ESLint 质量门禁。
- `useSubmissionSetupCommand.test.ts` 测试文件。

## 现象

阶段 1.3 复核未发现未使用变量或未使用导入问题，但 `npm --prefix terminal-ui run lint -- --quiet` 当前失败，唯一错误为 `perfectionist/sort-imports`：

```text
terminal-ui/src/__tests__/useSubmissionSetupCommand.test.ts
  9:1  error  Expected "../app/uiStore.js" to come before "../app/useSubmission.js"
```

## 根因

该测试文件的 parent imports 中，`../app/useSubmission.js` 排在了 `../app/uiStore.js` 前面，不符合项目 ESLint 中已启用的 `perfectionist/sort-imports` 自然排序规则。错误属于门禁排序问题，不是业务逻辑失败。

## 修复

把 `resetUiState` 的 import 移到 `useSubmission` 相关 import 前面，保持现有测试逻辑不变。

## 验证

```bash
npm --prefix terminal-ui run lint -- --quiet
npm --prefix terminal-ui test -- src/__tests__/useSubmissionSetupCommand.test.ts
```
