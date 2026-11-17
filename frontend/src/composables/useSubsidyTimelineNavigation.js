import { computed, ref, watch } from 'vue'
import { timelineTimeFromValue, timelineTimeValue } from '../utils/subsidy-timeline'

/** 两张预览图共用时间指针和缩放状态，回写相同缩放值时不重复更新。 */
export function useSubsidyTimelineNavigation(range) {
  const cursorAt = ref('')
  const zoom = ref({ start: 0, end: 100 })
  const hasRange = computed(() => range.value[0] < range.value[1])
  const viewRange = computed(() => {
    if (!hasRange.value) {
      return []
    }
    const start = timelineTimeValue(range.value[0])
    const span = timelineTimeValue(range.value[1]) - start
    return [
      timelineTimeFromValue(Math.ceil((start + (span * zoom.value.start) / 100) / 1000) * 1000),
      timelineTimeFromValue(start + (span * zoom.value.end) / 100),
    ]
  })
  function setCursor(value) {
    if (!hasRange.value || !value || !Number.isFinite(timelineTimeValue(value))) {
      return
    }
    cursorAt.value =
      value < range.value[0] ? range.value[0] : value > range.value[1] ? range.value[1] : value
    if (cursorAt.value < viewRange.value[0] || cursorAt.value > viewRange.value[1]) {
      resetZoom()
    }
  }
  function setZoom(value) {
    if (!Number.isFinite(value?.start) || !Number.isFinite(value?.end)) {
      return
    }
    const start = Math.min(95, Math.max(0, value.start))
    const end = Math.min(100, Math.max(start + 5, value.end))
    if (Math.abs(zoom.value.start - start) > 0.00001 || Math.abs(zoom.value.end - end) > 0.00001) {
      zoom.value = { start, end }
      if (cursorAt.value < viewRange.value[0]) {
        cursorAt.value = viewRange.value[0]
      }
      if (cursorAt.value > viewRange.value[1]) {
        cursorAt.value = viewRange.value[1]
      }
    }
  }
  function resetZoom() {
    zoom.value = { start: 0, end: 100 }
  }
  function showCurrent() {
    resetZoom()
    setCursor(range.value[1])
  }
  watch(
    range,
    () => {
      resetZoom()
      cursorAt.value = hasRange.value ? range.value[1] : ''
    },
    { immediate: true },
  )
  return { cursorAt, zoom, hasRange, viewRange, setCursor, setZoom, resetZoom, showCurrent }
}
