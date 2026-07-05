# 人格日记加载失败无可见反馈问题修复报告

## 问题现象

Web 人格日记页加载日记列表或读取某天日记失败时，页面没有持久错误提示。用户只能看到空态或旧内容，无法判断是没有日记还是后端请求失败。

## 影响范围

- 影响 `/solonclaw/persona/journal` 人格日记页面。
- 影响日记列表加载失败和单篇日记读取失败两条路径。
- 不影响人格文件编辑页。

## 根因

`web/src/views/solonclaw/PersonaDiaryView.vue` 中 `loadDiaryList()` 和 `selectDiary()` 直接等待 API 请求，没有 `catch` 分支，也没有持久 `loadError` 状态。`web/src/api/solonclaw/persona.ts` 的请求失败会抛出异常，视图层未转成用户可见反馈。

## 修复方案

- 增加 `loadError` 状态。
- 在列表加载和单篇读取前清理旧错误。
- 请求失败时保留错误消息，兜底使用 `personaDiary.loadFailed`。
- 在侧栏中渲染持久错误块，避免失败时只显示空态。

## 回归测试

新增 `web/tests/personaDiaryLoadFailureStatic.test.ts`，检查人格日记页必须保留并渲染加载错误，且不能只依赖控制台输出。

验证命令：

```powershell
node --experimental-strip-types tests/personaDiaryLoadFailureStatic.test.ts
npx vue-tsc -p tsconfig.app.json --noEmit --incremental false
npm run build
```

结果：三个验证命令均通过。
