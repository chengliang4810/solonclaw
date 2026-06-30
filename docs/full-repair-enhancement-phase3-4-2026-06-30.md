# 阶段 3.4 功能增强进度记录

日期：2026-06-30

## 对应能力点

- Dashboard-first setup：国内渠道扫码登录面板需要在失败、过期、等待和已扫码等状态下给出稳定反馈，避免用户看到空白区域。
- 国内渠道接入：飞书、钉钉、微信共享 QR 登录展示面板，增强应保留平台差异，同时提升错误态可见性。

## 已完成增强项

### 1. 无图二维码错误状态展示

- 提交：`9bb3e6740 fix: 展示无图二维码错误状态 / Show QR errors without images`
- `ChannelQrPanel.vue` 新增 `shouldShowStandaloneStatus(...)`，在没有二维码图片时仍为 `error` 和 `expired` 状态展示独立错误文案。
- `showEmptyStatus` 继续只控制微信这类无图等待、已扫码提示，不再限制错误和过期状态。
- `statusFallbackMessage(...)` 统一错误和过期状态的兜底文案，避免飞书、钉钉拉取二维码失败时只有按钮没有错误提示。
- 更新 `platformQrPanelReuseStatic.test.ts`，锁定共享 QR 面板的无图错误提示、兜底文案和微信无图等待分支。

验证：

- `npm --prefix web run test:platform-qr-panel-reuse`
- `npm --prefix web run test:platform-primary-setting-rows-reuse`
- `npm --prefix web run test:platform-optional-setting-rows-reuse`
- `npm --prefix web run build`
- `bun <omo-programming-skill>/scripts/typescript/check-no-excuse-rules.ts web/src/components/solonclaw/settings/ChannelQrPanel.vue web/tests/platformQrPanelReuseStatic.test.ts`
- `python3 scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

## 阶段状态

阶段 3.4 已完成 1 个低风险 UI 状态增强项。下一步可继续评估 `DisplaySettings.vue`、`ModelSettings.vue` 等设置页是否存在同类小增强，或转入阶段 4 的 AI/数据驱动审查。
