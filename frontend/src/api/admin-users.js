import { request } from './request'

const usersPath = '/api/admin/users'
const userPath = (id) => `${usersPath}/${encodeURIComponent(id)}`

export function listUsers({ page, pageSize, keyword, roleCode, status }, signal) {
  const params = new URLSearchParams({ page, pageSize })
  if (keyword) {
    params.set('keyword', keyword)
  }
  if (roleCode) {
    params.set('roleCode', roleCode)
  }
  if (status) {
    params.set('status', status)
  }
  return request(`${usersPath}?${params}`, { signal })
}

export function readUserStats(signal) {
  return request(`${usersPath}/stats`, { signal })
}

export function readUser(id, signal) {
  return request(userPath(id), { signal })
}

function write(path, method, body, signal) {
  return request(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
}
export function updateUserRoles(user, roleCodes, signal) {
  return write(
    userPath(user.id) + '/roles',
    'PATCH',
    { roleCodes, expectedVersion: user.authVersion },
    signal,
  )
}
export function updateUserStatus(user, status, signal) {
  return write(
    userPath(user.id) + '/status',
    'PATCH',
    { status, expectedVersion: user.authVersion },
    signal,
  )
}
export function readRoles(signal) {
  return request('/api/admin/roles', { signal })
}
export function saveRole(id, body, signal) {
  return write(
    '/api/admin/roles' + (id ? '/' + encodeURIComponent(id) : ''),
    id ? 'PUT' : 'POST',
    body,
    signal,
  )
}
export function readPlatformOwners(signal) {
  return request(usersPath + '/platform-assignments', { signal })
}
export function updatePlatformTeam(team, members, signal) {
  return write(
    usersPath +
      '/platform-assignments/' +
      encodeURIComponent(team.perspective) +
      '/' +
      encodeURIComponent(team.platformCode),
    'PUT',
    { expectedVersion: team.version, members },
    signal,
  )
}
export function findPlatformCandidates(keyword, page, signal) {
  return request(
    usersPath + '/platform-candidates?' + new URLSearchParams({ keyword, page, pageSize: 20 }),
    { signal },
  )
}
