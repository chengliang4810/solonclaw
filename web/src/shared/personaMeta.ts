export type PersonaMeta = {
  title: string
  fileName: string
  description: string
}

const PERSONA_META: Record<string, PersonaMeta> = {
  agents: {
    title: '工作区规则',
    fileName: 'AGENTS.md',
    description: '这里维护当前工作区的目标、约束和实现边界，决定智能体在本仓库里的默认行为。',
  },
  memory: {
    title: '长期记忆',
    fileName: 'MEMORY.md',
    description: '这里沉淀跨会话仍要保留的偏好、事实结论和长期上下文。',
  },
  memory_today: {
    title: '今日记忆',
    fileName: 'memory/YYYY-MM-DD.md',
    description: '这里记录当天运行过程中的临时沉淀，通常按日期自动生成。',
  },
  soul: {
    title: '人格设定',
    fileName: 'SOUL.md',
    description: '这里定义智能体的人格、表达方式和稳定行为倾向。',
  },
  identity: {
    title: '身份设定',
    fileName: 'IDENTITY.md',
    description: '这里记录当前实例的身份信息和对外自我介绍内容。',
  },
  user: {
    title: '用户档案',
    fileName: 'USER.md',
    description: '这里记录用户偏好、协作习惯和长期稳定需求。',
  },
  tools: {
    title: '工具笔记',
    fileName: 'TOOLS.md',
    description: '这里整理工具能力、使用约束和调用时需要遵守的额外说明。',
  },
  heartbeat: {
    title: '心跳规则',
    fileName: 'HEARTBEAT.md',
    description: '这里定义周期性提醒、静默心跳和后台运行时要遵守的规则。',
  },
}

export function getPersonaMeta(key: string): PersonaMeta {
  return PERSONA_META[key] || {
    title: key,
    fileName: key,
    description: '这里查看该上下文文件的当前内容。',
  }
}
