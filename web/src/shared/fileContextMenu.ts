import type { FileEntry } from '../api/solonclaw/files.ts'
import type { MenuProps } from 'antdv-next'
import { isImageFile, isMarkdownFile, isTextFile } from './fileTypes.ts'

export type FileContextMenuActionKey = 'open' | 'edit' | 'preview' | 'download' | 'restoreDefault' | 'copyPath'

export interface FileContextMenuAction {
  readonly key: FileContextMenuActionKey
  readonly labelKey: string
  readonly visible: (entry: FileEntry) => boolean
}

export type FileContextMenuItem = NonNullable<MenuProps['items']>[number]

export type FileContextMenuTranslator = (key: string) => string

export const FILE_CONTEXT_ACTIONS: readonly FileContextMenuAction[] = [
  {
    key: 'open',
    labelKey: 'files.open',
    visible: entry => entry.isDir,
  },
  {
    key: 'edit',
    labelKey: 'files.edit',
    visible: entry => !entry.isDir && isTextFile(entry.name),
  },
  {
    key: 'preview',
    labelKey: 'files.preview',
    visible: entry => !entry.isDir && (isImageFile(entry.name) || isMarkdownFile(entry.name)),
  },
  {
    key: 'download',
    labelKey: 'files.download',
    visible: entry => !entry.isDir,
  },
  {
    key: 'restoreDefault',
    labelKey: 'files.restoreDefault',
    visible: entry => !entry.isDir,
  },
  {
    key: 'copyPath',
    labelKey: 'files.copyPath',
    visible: () => true,
  },
] as const

export function buildFileContextMenuItems(
  entry: FileEntry | null,
  t: FileContextMenuTranslator,
): FileContextMenuItem[] {
  if (!entry) return []

  const primaryItems: FileContextMenuItem[] = FILE_CONTEXT_ACTIONS
    .filter(action => action.key !== 'copyPath' && action.visible(entry))
    .map(action => ({
      label: t(action.labelKey),
      key: action.key,
    }))

  const copyPathAction = FILE_CONTEXT_ACTIONS.find(action => action.key === 'copyPath')
  if (!copyPathAction || !copyPathAction.visible(entry)) return primaryItems

  return [
    ...primaryItems,
    { type: 'divider', key: 'd1' },
    { label: t(copyPathAction.labelKey), key: copyPathAction.key },
  ]
}
