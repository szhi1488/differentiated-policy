/** 在用户点击时复制原文，两种复制方式均不可用时由页面提供手动复制。 */
export async function copyText(text) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 部分嵌入页面提供 Clipboard API，但不允许应用直接写入。
  }

  if (typeof document.execCommand !== 'function') {
    return false
  }
  const previousFocus = document.activeElement
  const field = document.createElement('textarea')
  field.value = text
  field.readOnly = true
  field.tabIndex = -1
  field.style.cssText = 'position:fixed;top:0;left:0;opacity:0;pointer-events:none'
  document.body.append(field)
  try {
    field.focus({ preventScroll: true })
    field.select()
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    field.remove()
    previousFocus?.focus?.({ preventScroll: true })
  }
}
