import { existsSync } from 'node:fs'
import { preview } from 'vite'

const role = process.argv[2] ?? 'APPLICANT'
const ports = { APPLICANT: 4174, ADMIN: 4175, GUEST: 4176 }
if (!Object.hasOwn(ports, role)) {
  throw new Error('预览模式必须为 APPLICANT、ADMIN 或 GUEST。')
}
if (!existsSync(new URL('../dist/index.html', import.meta.url))) {
  throw new Error('找不到前端构建产物，请先运行 npm run build。')
}

// 只在本地预览服务器模拟会话。正式构建继续从服务端获取身份。
const server = await preview({
  preview: { host: '127.0.0.1', port: ports[role], strictPort: true, proxy: {} },
  plugins: [
    {
      name: 'qa-preview-session',
      configurePreviewServer({ middlewares }) {
        middlewares.use('/api', (request, response) => {
          response.setHeader('Content-Type', 'application/json; charset=utf-8')
          response.setHeader('Cache-Control', 'no-store')
          if (request.method === 'GET' && request.url === '/auth/session') {
            if (role === 'GUEST') {
              response.statusCode = 401
              response.end(JSON.stringify({ message: '未登录' }))
              return
            }
            response.end(
              JSON.stringify({
                user: { id: 'qa', tenantKey: 'qa', name: '界面验收', roleType: role },
              }),
            )
            return
          }
          if (role === 'GUEST' && request.method === 'GET' && request.url === '/feishu/config') {
            response.end(JSON.stringify({ sdkEnabled: true }))
            return
          }
          response.statusCode = 501
          response.end(JSON.stringify({ message: '本地预览仅提供模拟会话。' }))
        })
      },
    },
  ],
})

console.log(`界面验收预览（${role}，模拟会话）`)
server.printUrls()
