import type { AvailableModelGroup } from '@/api/jimuqu/system'

export interface ModelPickerItem {
  key: string
  model: string
  provider: string
}

export function visibleModelPickerItems(
  groups: AvailableModelGroup[],
  collapsedGroups: Record<string, boolean>,
): ModelPickerItem[] {
  return groups.flatMap((group) => {
    if (collapsedGroups[group.provider]) return []
    return group.models.map((model) => ({
      key: `${group.provider}:${model}`,
      model,
      provider: group.provider,
    }))
  })
}
