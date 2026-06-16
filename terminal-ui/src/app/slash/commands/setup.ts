import { patchOverlayState } from '../../overlayStore.js'
import type { SlashCommand } from '../types.js'

const setupUsage = [
  '设置：',
  '  /setup model    配置提供方与模型',
  '  /setup gateway  配置国内消息渠道'
].join('\n')

export const setupCommands: SlashCommand[] = [
  {
    help: '就地配置模型与渠道',
    name: 'setup',
    run: (arg, ctx) => {
      const target = arg.trim().toLowerCase()

      if (!target) {
        return patchOverlayState({ channelSetup: false, modelPicker: false, setupPanel: true })
      }

      if (target === 'model') {
        return patchOverlayState({ channelSetup: false, modelPicker: true, setupPanel: false })
      }

      if (target === 'gateway' || target === 'channel' || target === 'channels') {
        return patchOverlayState({ channelSetup: true, modelPicker: false, setupPanel: false })
      }

      ctx.transcript.page(setupUsage, '设置')
    }
  }
]
