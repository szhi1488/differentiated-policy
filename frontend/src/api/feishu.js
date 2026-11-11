import { request } from './request'

export function getFeishuConfig() {
  return request('/api/feishu/config')
}

export function getJsSdkSignature(url) {
  return request('/api/feishu/js-sdk/signature', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }),
  })
}

export async function getLoginSession() {
  try {
    return await request('/api/auth/session')
  } catch (error) {
    if (error.status === 401) {
      return null
    }
    throw error
  }
}

export function createLoginChallenge() {
  return request('/api/auth/challenge', { method: 'POST' })
}

export function exchangeLoginCode(code, state) {
  return request('/api/auth/feishu/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, state }),
  })
}

export function logout() {
  return request('/api/auth/logout', { method: 'POST' })
}

export function createBrowserAuthorization() {
  return request('/api/auth/feishu/browser/authorize', { method: 'POST' })
}

export function exchangeBrowserLoginCode(code, state) {
  return request('/api/auth/feishu/browser/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, state }),
  })
}
