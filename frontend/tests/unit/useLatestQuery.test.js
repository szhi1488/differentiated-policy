import { effectScope } from 'vue'
import { expect, it } from 'vitest'
import { useLatestQuery } from '../../src/composables/useLatestQuery'
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
it('新查询中止旧请求，旧响应晚到及卸载后均不能写回，失败不会显示旧成功', async () => {
  const scope = effectScope()
  const state = scope.run(useLatestQuery)
  const old = deferred(),
    latest = deferred()
  let oldSignal
  const first = state.run((signal) => {
    oldSignal = signal
    return old.promise
  })
  const second = state.run(() => latest.promise)
  expect(oldSignal.aborted).toBe(true)
  latest.resolve(['新结果'])
  await second
  old.resolve(['过期结果'])
  await first
  expect(state.data.value).toEqual(['新结果'])
  await state.run(async () => {
    throw new Error('失败')
  })
  expect(state.status.value).toBe('error')
  expect(state.data.value).toBeNull()
  const late = deferred()
  const third = state.run(() => late.promise)
  scope.stop()
  late.resolve(['卸载后'])
  await third
  expect(state.data.value).toBeNull()
})
it('主动取消不显示错误，空列表是空态', async () => {
  const scope = effectScope(),
    state = scope.run(useLatestQuery)
  await state.run(async () => {
    throw { kind: 'cancelled' }
  })
  expect(state.error.value).toBeNull()
  await state.run(async () => [])
  expect(state.status.value).toBe('empty')
  scope.stop()
})
