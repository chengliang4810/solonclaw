export const completeVSCodeKeybindingsJson = () =>
  JSON.stringify([
    {
      key: 'cmd+c',
      command: 'workbench.action.terminal.sendSequence',
      when: 'terminalFocus && terminalTextSelected',
      args: { text: '\u001b[99;13u' }
    },
    {
      key: 'shift+enter',
      command: 'workbench.action.terminal.sendSequence',
      when: 'terminalFocus',
      args: { text: '\\\r\n' }
    },
    {
      key: 'ctrl+enter',
      command: 'workbench.action.terminal.sendSequence',
      when: 'terminalFocus',
      args: { text: '\\\r\n' }
    },
    {
      key: 'cmd+enter',
      command: 'workbench.action.terminal.sendSequence',
      when: 'terminalFocus',
      args: { text: '\\\r\n' }
    },
    {
      key: 'cmd+z',
      command: 'workbench.action.terminal.sendSequence',
      when: 'terminalFocus',
      args: { text: '\u001b[122;9u' }
    },
    {
      key: 'shift+cmd+z',
      command: 'workbench.action.terminal.sendSequence',
      when: 'terminalFocus',
      args: { text: '\u001b[122;10u' }
    }
  ])
