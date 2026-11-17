import { request } from './request'

const usersPath = '/api/admin/users'
const userPath = (id) => `${usersPath}/${encodeURIComponent(id)}`

export function listUsers({ page, pageSize, keyword, roleType, status }, signal) {
  const params = new URLSearchParams({ page, pageSize })
  if (keyword) {
    params.set('keyword', keyword)
  }
  if (roleType) {
    params.set('roleType', roleType)
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

function patchUser(path, body, signal) {
  return request(path, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
}

export function updateUserRole(user, roleType, signal) {
  return patchUser(
    `${userPath(user.id)}/role`,
    {
      roleType,
      expectedRoleType: user.roleType,
      expectedStatus: user.status,
    },
    signal,
  )
}

export function updateUserStatus(user, status, signal) {
  return patchUser(
    `${userPath(user.id)}/status`,
    {
      status,
      expectedRoleType: user.roleType,
      expectedStatus: user.status,
    },
    signal,
  )
}
