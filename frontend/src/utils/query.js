export const queryText = (value) => (typeof value === 'string' ? value : '')

export function queryDate(value) {
  const text = queryText(value)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) {
    return ''
  }
  const [year, month, day] = text.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  return date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
    ? text
    : ''
}
