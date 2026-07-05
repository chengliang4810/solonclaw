# 渠道媒体详情错误不可见问题报告

## 问题

渠道媒体缓存中点击媒体详情时，如果详情接口失败，页面没有任何用户可见错误；刷新详情失败时也只结束 loading，不提示失败原因。

## 根因

`openMediaDetail()` 和 `refreshSelectedMedia()` 没有捕获 API 异常。列表加载、下载、引用等相邻路径已有 `message.error`，详情路径缺失同样的错误反馈。

## 修复

为详情打开和详情刷新补充 `catch` 分支，复用已有媒体加载失败文案作为兜底错误提示。

## 验证

- `node --experimental-strip-types tests\channelMediaUiStatic.test.ts`
- `npx vue-tsc -p tsconfig.app.json --noEmit --incremental false`
