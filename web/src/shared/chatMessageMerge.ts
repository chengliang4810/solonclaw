export interface ChatMergeMessage {
  role: string
  content?: string
}

function lastUserIndex(messages: ChatMergeMessage[]): number {
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    if (messages[i]?.role === 'user') return i
  }
  return -1
}

function lastAssistantAfterUser(
  messages: ChatMergeMessage[],
  userIndex: number,
): ChatMergeMessage | undefined {
  for (let i = messages.length - 1; i > userIndex; i -= 1) {
    if (messages[i]?.role === 'assistant') return messages[i]
  }
  return undefined
}

function isCancelErrorMessage(message?: ChatMergeMessage): boolean {
  return message?.content === 'Error: Run canceled'
}

function sameLastUser(local: ChatMergeMessage[], server: ChatMergeMessage[]): boolean {
  const localLastUser = [...local].reverse().find(m => m.role === 'user')
  const serverLastUser = [...server].reverse().find(m => m.role === 'user')
  return !!localLastUser
    && !!serverLastUser
    && localLastUser.content === serverLastUser.content
}

function serverHasNewCompletedTurn(local: ChatMergeMessage[], server: ChatMergeMessage[]): boolean {
  const serverLastUserIndex = lastUserIndex(server)
  if (serverLastUserIndex < 0) return false

  const serverLastUser = server[serverLastUserIndex]
  if (!serverLastUser?.content) return false

  const localAlreadyHasUser = local.some(message =>
    message.role === 'user' && message.content === serverLastUser.content,
  )
  if (localAlreadyHasUser) return false

  const serverLastAssistant = lastAssistantAfterUser(server, serverLastUserIndex)
  return !!serverLastAssistant?.content?.trim()
}

function isPrefixOfServer(local: ChatMergeMessage[], server: ChatMergeMessage[]): boolean {
  if (server.length <= local.length) return false
  for (let i = 0; i < local.length; i += 1) {
    const left = local[i]
    const right = server[i]
    if (!left || !right || left.role !== right.role || left.content !== right.content) {
      return false
    }
  }
  return true
}

export function shouldUseServerMessages(
  local: ChatMergeMessage[],
  server: ChatMergeMessage[],
): boolean {
  const localLastUserIndex = lastUserIndex(local)
  const serverLastUserIndex = lastUserIndex(server)
  const localLastAssistant = lastAssistantAfterUser(local, localLastUserIndex)
  const serverLastAssistant = lastAssistantAfterUser(server, serverLastUserIndex)
  const localAssistantLen = localLastAssistant?.content?.length ?? 0
  const serverAssistantLen = serverLastAssistant?.content?.length ?? 0
  const localUsers = local.filter(m => m.role === 'user').length
  const serverUsers = server.filter(m => m.role === 'user').length
  const localLast = local[local.length - 1]
  const serverLast = server[server.length - 1]

  // 用户主动停止运行后，SSE 本地缓存会留下 "Error: Run canceled"。
  // 后端会话持久化完成后应以服务端的可恢复取消提示为准，避免刷新页面又回退到临时流错误。
  if (
    isCancelErrorMessage(localLast)
    && sameLastUser(local, server)
    && serverLast?.role === 'assistant'
    && serverLast.content?.trim()
  ) {
    return true
  }

  // 定时任务、通知等异步回投会在同一个用户轮次后追加新的 assistant 消息。
  // 这类输出可能比上一条最终回复更短，不能只用最后 assistant 文本长度判断服务端是否“追上”。
  if (sameLastUser(local, server) && isPrefixOfServer(local, server)) {
    return true
  }

  // 上下文压缩会把早期多轮消息折叠成一条摘要，导致服务端 user 轮次数少于本地缓存。
  // 只要服务端映射后的消息总量已经更多，就说明服务端持久化视图包含本地没有的新结果。
  if (server.length > local.length) {
    return true
  }

  // 长会话压缩后，服务端消息可能比浏览器本地缓存更短；如果服务端已经出现
  // 本地缓存没有的新用户轮次和对应助手回复，应使用服务端，避免 UI 停在上一轮。
  if (serverHasNewCompletedTurn(local, server)) {
    return true
  }

  return serverUsers > localUsers
    || (
      serverUsers === localUsers
      && sameLastUser(local, server)
      && serverAssistantLen >= localAssistantLen
    )
}
