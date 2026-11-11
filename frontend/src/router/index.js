import { createRouter, createWebHashHistory } from 'vue-router'
import { workspaceDestination } from '../utils/workspace-route'

const EmptyRoute = { render: () => null }
export function createWorkspaceRouter(history = createWebHashHistory()) {
  let sessionUser = null
  const router = createRouter({
    history,
    routes: [
      { path: '/admin/overview', component: () => import('../views/admin/AdminOverview.vue') },
      { path: '/admin/policies', component: () => import('../views/admin/AdminPolicies.vue') },
      {
        path: '/admin/reviews',
        component: () => import('../views/admin/AdminPolicies.vue'),
        props: { reviewOnly: true },
      },
      {
        path: '/admin/catalog/:section(sync|sources|exceptions|variants|logs)',
        component: () => import('../views/admin/AdminCatalog.vue'),
        props: true,
      },
      { path: '/admin/settings', component: () => import('../views/admin/AdminSettings.vue') },
      { path: '/:pathMatch(.*)*', component: EmptyRoute },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
  router.beforeEach((to) =>
    sessionUser ? workspaceDestination(sessionUser, to.path) || true : true,
  )
  router.setSessionUser = async (user) => {
    sessionUser = user
    const destination = workspaceDestination(user, router.currentRoute.value.path)
    if (destination) {
      await router.replace(destination)
    }
  }
  return router
}
