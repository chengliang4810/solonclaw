import { patchOverlayState } from '../../overlayStore.js'
import type { SlashCommand } from '../types.js'

const setupUsage = [
  'setup:',
  '  /setup model    configure provider and model',
  '  /setup gateway  configure domestic messaging channels'
].join('\n')

export const setupCommands: SlashCommand[] = [
  {
    help: 'configure model and gateway in-place',
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

      ctx.transcript.page(setupUsage, 'Setup')
    }
  }
]
