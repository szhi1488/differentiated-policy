import { createRouter, createWebHashHistory } from 'vue-router'
import { workspaceDestination } from '../utils/workspace-route'
import { authSession } from '../composables/useAuthSession'

const EmptyRoute = { render: () => null }
export function createWorkspaceRouter(history = createWebHashHistory(), session = authSession) {
  const router = createRouter({
    history,
    routes: [
      {
        path: '/apply',
        name: 'apply',
        meta: { role: 'APPLICANT' },
        component: () => import('../views/apply/PolicyApplicationPage.vue'),
      },
      {
        path: '/apply/submissions',
        name: 'submissions',
        meta: { role: 'APPLICANT' },
        component: () => import('../views/apply/MySubmissionsPage.vue'),
      },
      {
        path: '/admin/catalog/sources',
        name: 'catalog-sources',
        meta: { role: 'ADMIN' },
        component: () => import('../views/admin/catalog/CatalogSourcesPage.vue'),
      },
      {
        path: '/admin/catalog/cleaning',
        name: 'catalog-cleaning',
        meta: { role: 'ADMIN' },
        component: () => import('../views/admin/catalog/CatalogCleaningPage.vue'),
      },
      {
        path: '/admin/catalog/center',
        name: 'catalog-center',
        meta: { role: 'ADMIN' },
        component: () => import('../views/admin/catalog/CatalogCenterPage.vue'),
      },
      { path: '/:pathMatch(.*)*', component: EmptyRoute },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
  router.beforeEach((to) =>
    session.user.value ? workspaceDestination(session.user.value, to) || true : true,
  )
  router.syncSessionRoute = async () => {
    const destination = workspaceDestination(session.user.value, router.currentRoute.value)
    if (destination) {
      await router.replace(destination)
    }
  }
  return router
}
