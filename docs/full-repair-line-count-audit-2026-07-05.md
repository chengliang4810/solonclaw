# 超长代码文件审计记录（2026-07-05）

## 范围

- Java、TypeScript、TSX、Vue、JavaScript、CSS、SCSS 源码文件。
- 排除 `node_modules`、`dist`、`target`、`runtime`、`workspace` 等依赖、构建和运行产物目录。

## 结论

- 当前源码范围内未发现超过 4000 行的代码文件。
- 阶段 1.2 当前没有需要拆分的代码文件；后续新增超长文件时再按自然模块边界拆分。

## 验证命令

```powershell
$patterns = @('*.java','*.ts','*.tsx','*.vue','*.js','*.mjs','*.css','*.scss')
$files = foreach ($pattern in $patterns) { rg --files -g $pattern -g '!web/node_modules/**' -g '!web/dist/**' -g '!terminal-ui/node_modules/**' -g '!terminal-ui/dist/**' -g '!terminal-ui/packages/**/dist/**' -g '!target/**' -g '!runtime/**' -g '!workspace/**' }
$files | Sort-Object -Unique | ForEach-Object {
  $count = (Get-Content -LiteralPath $_).Count
  if ($count -gt 4000) { [pscustomobject]@{ Lines = $count; File = $_ } }
} | Sort-Object Lines -Descending | Format-Table -AutoSize
```

命令输出为空，表示没有命中项。
