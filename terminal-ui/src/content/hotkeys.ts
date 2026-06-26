import { isMac, isRemoteShell } from '../lib/platform.js'

const action = isMac ? 'Cmd' : 'Ctrl'
const paste = isMac ? 'Cmd' : 'Alt'

const copyHotkeys: [string, string][] = isMac
  ? [
      ['Cmd+C', '复制选中'],
      ['Ctrl+C', '中断 / 清除草稿 / 退出']
    ]
  : isRemoteShell()
    ? [
        ['Cmd+C', '终端转发时复制选中'],
        ['Ctrl+C', '复制选中 / 中断 / 清除草稿 / 退出']
      ]
    : [['Ctrl+C', '复制选中 / 中断 / 清除草稿 / 退出']]

export const HOTKEYS: [string, string][] = [
  ...copyHotkeys,
  [action + '+D', '退出'],
  [action + '+G / Alt+G', '打开 $EDITOR（Alt+G 兼容 VSCode/Cursor）'],
  [action + '+L', '重绘屏幕'],
  [paste + '+V / /paste', '粘贴文本；/paste 附加剪贴板图片'],
  ['Tab', '应用补全'],
  ['↑/↓', '补全 / 编辑队列 / 历史'],
  ['Ctrl+X', '打开实时会话切换器'],
  [action + '+A/E', '行首 / 行尾'],
  [action + '+Z / ' + action + '+Y', '撤销 / 重做'],
  [action + '+W', '删除单词'],
  [action + '+U/K', '删除到行首 / 行尾'],
  [action + '+←/→', '跳转单词'],
  ['Home/End', '行首 / 行尾'],
  ['Shift+Enter / Alt+Enter', '插入换行'],
  ['\\+Enter', '多行续行（备用）'],
  ['!<cmd>', '运行 Shell 命令（如 !ls、!git status）'],
  ['{!<cmd>}', '内嵌 Shell 输出（如 "分支是 {!git branch --show-current}"）']
]
