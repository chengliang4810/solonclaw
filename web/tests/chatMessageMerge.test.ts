import assert from 'node:assert/strict'
import { shouldUseServerMessages, type ChatMergeMessage } from '../src/shared/chatMessageMerge.ts'

function msg(role: string, content: string): ChatMergeMessage {
  return { role, content }
}

const user = msg('user', '创建一个定时任务并等待回投')
const finalReply = msg(
  'assistant',
  '{"marker":"web-loop-origin-cron-after-fix-20260613-0748","next_slice":"等待自然触发并回投"}',
)
const cronCallback = msg(
  'assistant',
  'WEB_LOOP_ORIGIN_CRON_AFTER_FIX_OK web-loop-origin-cron-after-fix-20260613-0748',
)

assert.equal(
  shouldUseServerMessages(
    [user, finalReply],
    [user, finalReply, cronCallback],
  ),
  true,
)

assert.equal(
  shouldUseServerMessages(
    [
      msg('user', '第一轮 todo'),
      msg('assistant', '第一轮完成'),
      msg('user', '第二轮 cron'),
      msg('assistant', '第二轮完成'),
      msg('user', '第三轮刷新'),
      msg('assistant', '第三轮完成'),
    ],
    [
      msg('assistant', '[CONTEXT COMPACTION] 第一到第三轮已压缩为摘要'),
      msg('user', '第四轮创建 origin cron'),
      msg('assistant', '{"marker":"web-loop-origin-cron-after-fix-20260613-0748"}'),
      cronCallback,
      msg('assistant', '下一轮继续检查 UI 可见性'),
      msg('assistant', '日志未新增错误'),
      msg('assistant', '服务端消息总量已经领先本地缓存'),
    ],
  ),
  true,
)

assert.equal(
  shouldUseServerMessages(
    [
      msg('user', '第一轮 todo'),
      msg('assistant', '第一轮完成'),
      msg('user', '上一轮精准日志检索'),
      msg('assistant', '{"marker":"web-loop-log-query-agent-20260613-1016"}'),
    ],
    [
      msg('assistant', '[CONTEXT COMPACTION] 早期长期回归轮次已压缩'),
      msg('user', '会话恢复只读验证 marker=web-loop-recovery-log-ui-20260613-1032'),
      msg('assistant', '{"marker":"web-loop-recovery-log-ui-20260613-1032","failure_layer":"none"}'),
    ],
  ),
  true,
)

assert.equal(
  shouldUseServerMessages(
    [user, msg('assistant', '本地流式回复已经很长，仍在等待最终持久化完成。')],
    [user, msg('assistant', '短回复')],
  ),
  false,
)

assert.equal(
  shouldUseServerMessages(
    [
      msg('user', '上一轮已完成'),
      msg('assistant', '上一轮完成'),
      msg('user', '刷新时最新用户输入仍在本地'),
      msg('assistant', '本地已经开始流式输出'),
    ],
    [
      msg('user', '上一轮已完成'),
      msg('assistant', '上一轮完成'),
    ],
  ),
  false,
)

assert.equal(
  shouldUseServerMessages(
    [user, msg('assistant', 'Error: Run canceled')],
    [user, msg('assistant', '运行已取消，当前会话可以恢复。')],
  ),
  true,
)
