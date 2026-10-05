import assert from 'node:assert/strict'
import test from 'node:test'

// Exercise the real event handlers with only Chrome and DOM I/O replaced.
class Element {
  constructor() {
    this.value = ''
    this.hidden = true
    this.disabled = false
    this.className = ''
    this.children = []
    this.dataset = {}
    this.handlers = new Map()
    this.classList = {
      toggle: (name, enabled) => {
        const names = new Set(this.className.split(' ').filter(Boolean))
        if (enabled) names.add(name)
        else names.delete(name)
        this.className = [...names].join(' ')
      },
      add: (...names) => names.forEach(name => this.classList.toggle(name, true)),
      remove: (...names) => names.forEach(name => this.classList.toggle(name, false))
    }
  }
  addEventListener(name, handler) { this.handlers.set(name, handler) }
  replaceChildren(...children) { this.children = children }
  append(...children) { this.children.push(...children) }
}

const flush = () => new Promise(resolve => setImmediate(resolve))

test('401 during import or automatic routing keeps the login view and stops submission', async () => {
  for (const failAt of ['upload', 'poll', 'route']) {
    const elements = new Map()
    const element = id => {
      if (!elements.has(id)) elements.set(id, new Element())
      return elements.get(id)
    }
    globalThis.document = { getElementById: element, createElement: () => new Element() }
    let token = 'expired-token'
    globalThis.chrome = {
      storage: { local: {
        get: async () => ({ authToken: token }),
        set: async values => { token = values.authToken },
        remove: async () => { token = null }
      } },
      tabs: { query: async () => [] }
    }
    const requests = []
    globalThis.fetch = async (url, options) => {
      requests.push(url)
      if (url.endsWith('/media/upload-url/job-uuid')) {
        if (failAt === 'poll') return new Response('expired', { status: 401 })
        return Response.json({ code: 0, message: 'success', data: { id: 'job-uuid', status: 'COMPLETED', media: { id: 7 } } })
      }
      if (url.endsWith('/media/upload-url')) {
        assert.equal(options.body.get('url'), 'https://vimeo.com/1234')
        if (failAt === 'upload') return new Response('expired', { status: 401 })
        return Response.json({ code: 0, message: 'success', data: { id: 'job-uuid', status: 'QUEUED' } })
      }
      if (url.includes('/analysis/route')) return new Response('expired', { status: 401 })
      throw new Error(`unexpected request: ${url}`)
    }
    await import(`../sidepanel.js?auth-case=${failAt}`)
    await flush()
    element('video-url').value = 'vimeo.com/1234'
    element('goal').value = 'summarize the video'
    element('mode').value = 'AUTO'
    await element('submit-btn').handlers.get('click')()
    assert.equal(element('login-view').hidden, false)
    assert.equal(element('ready-view').hidden, true)
    assert.equal(element('running-view').hidden, true)
    assert.equal(element('submit-btn').disabled, false)
    assert.equal(element('result').className, 'result')
    assert.equal(token, null)
    assert.equal(requests.some(url => url.includes('/analysis/ai?')), false)
  }
})

test('URL analysis waits for the download and uses media.id; failed or cancelled jobs never submit analysis', async () => {
  for (const outcome of ['completed', 'failed', 'cancelled']) {
    const elements = new Map()
    const element = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id) }
    globalThis.document = { getElementById: element, createElement: () => new Element() }
    globalThis.chrome = { storage: { local: { get: async () => ({ authToken: 'fixture' }), set: async () => {}, remove: async () => {} } }, tabs: { query: async () => [] } }
    const requests = []
    let finishPoll
    globalThis.fetch = async (url, options) => {
      requests.push({ url, method: options?.method || 'GET' })
      if (url.endsWith('/media/upload-url')) return Response.json({ code: 0, message: 'ok', data: { id: 'download-uuid', status: 'QUEUED' } }, { status: 202 })
      if (url.endsWith('/media/upload-url/download-uuid')) {
        if (outcome === 'cancelled') return new Promise(resolve => { finishPoll = resolve })
        return Response.json({ code: 0, message: 'ok', data: outcome === 'failed'
          ? { id: 'download-uuid', status: 'FAILED', error: 'download failed' }
          : { id: 'download-uuid', status: 'COMPLETED', media: { id: 42 } } })
      }
      if (url.includes('/analysis/ai?')) {
        assert.equal(new URL(url).searchParams.get('id'), '42')
        return new Response('fixture stops before stream', { status: 503 })
      }
      throw new Error(`unexpected request ${url}`)
    }
    await import(`../sidepanel.js?async-case=${outcome}`); await flush()
    element('video-url').value = 'vimeo.com/1234'; element('goal').value = 'summarize'; element('mode').value = 'GENERAL'
    const submission = element('submit-btn').handlers.get('click')()
    if (outcome === 'cancelled') {
      while (!finishPoll) await flush()
      assert.equal(element('back-btn').hidden, false)
      await element('back-btn').handlers.get('click')()
      finishPoll(Response.json({ code: 0, message: 'ok', data: { status: 'COMPLETED', media: { id: 42 } } }))
    }
    await submission
    assert.equal(requests.some(item => item.url.includes('/analysis/ai?')), outcome === 'completed')
    assert.equal(requests.filter(item => item.url.endsWith('/media/upload-url')).length, 1)
    assert.equal(requests.filter(item => item.url.endsWith('/media/upload-url/download-uuid')).length, 1)
  }
})
