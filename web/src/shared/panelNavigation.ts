export type PanelNavigationAction = 'up' | 'down' | 'home' | 'end' | 'select' | 'cancel' | 'none'

export function normalizePanelKey(key: string): PanelNavigationAction {
  if (key === 'ArrowUp') return 'up'
  if (key === 'ArrowDown') return 'down'
  if (key === 'Home') return 'home'
  if (key === 'End') return 'end'
  if (key === 'Enter') return 'select'
  if (key === 'Escape') return 'cancel'
  return 'none'
}

export function nextPanelCursor(current: number, action: PanelNavigationAction, itemCount: number): number {
  if (itemCount <= 0) return 0
  if (current < 0) return 0
  if (current >= itemCount) return itemCount - 1
  const cursor = current
  if (action === 'up') return (cursor - 1 + itemCount) % itemCount
  if (action === 'down') return (cursor + 1) % itemCount
  if (action === 'home') return 0
  if (action === 'end') return itemCount - 1
  return cursor
}
