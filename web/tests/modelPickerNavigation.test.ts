import assert from 'node:assert/strict'
import { visibleModelPickerItems } from '../src/shared/modelPicker.ts'

const groups = [
  {
    provider: 'default',
    providerKey: 'default',
    label: 'Default',
    base_url: 'https://example.invalid',
    models: ['same-name', 'fast-model'],
    dialect: 'openai-responses',
    has_api_key: true,
    isDefault: true,
  },
  {
    provider: 'backup',
    providerKey: 'backup',
    label: 'Backup',
    base_url: 'https://backup.invalid',
    models: ['same-name', 'deep-model'],
    dialect: 'openai',
    has_api_key: true,
    isDefault: false,
  },
]

assert.deepEqual(
  visibleModelPickerItems(groups, {}).map((item) => item.key),
  ['default:same-name', 'default:fast-model', 'backup:same-name', 'backup:deep-model'],
)

assert.deepEqual(
  visibleModelPickerItems(groups, { backup: true }).map((item) => item.key),
  ['default:same-name', 'default:fast-model'],
)

assert.deepEqual(visibleModelPickerItems([], {}).map((item) => item.key), [])
