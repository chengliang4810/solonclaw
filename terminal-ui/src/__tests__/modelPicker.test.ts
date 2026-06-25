import { describe, expect, it } from 'vitest'

import { modelKeyStorageHint } from '../components/modelPicker.js'

describe('model picker setup copy', () => {
  it('tells users API keys are saved to workspace/config.yml', () => {
    expect(modelKeyStorageHint()).toBe('Paste your API key below (saved to workspace/config.yml)')
  })
})
