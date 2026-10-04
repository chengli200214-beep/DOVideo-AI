/** Schedules read-only refresh callbacks; callers must never pass a submission action. */
export function createReadPolling({ refresh, active, allowed = () => true, schedule = setTimeout,
  cancel = clearTimeout, eventTarget = typeof window === 'undefined' ? null : window,
  interval = 5000, maxFailures = 5 }) {
  let timer = null, failures = 0, disposed = false, generation = 0
  function stop() { generation += 1; if (timer !== null) cancel(timer); timer = null }
  function later(delay) {
    stop()
    if (disposed || !allowed()) return false
    const current = generation
    timer = schedule(() => {
      if (disposed || current !== generation || !allowed()) return
      timer = null
      return refresh()
    }, delay)
    return true
  }
  function succeeded() { failures = 0; stop(); if (active()) later(interval) }
  function failed(error) {
    stop()
    const status = error?.status
    const retryable = error?.name !== 'AbortError' && (!status || status === 408 || status === 429 || status >= 500)
    if (!retryable || failures >= maxFailures) return false
    failures += 1
    return later(Math.min(interval * 2 ** (failures - 1), 60000))
  }
  function reset() { stop(); failures = 0 }
  function online() { if (!disposed && allowed()) { reset(); return refresh() } }
  eventTarget?.addEventListener?.('online', online)
  return { stop, reset, succeeded, failed,
    dispose() { disposed = true; stop(); eventTarget?.removeEventListener?.('online', online) } }
}
