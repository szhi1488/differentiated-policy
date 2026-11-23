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
        path: '/apply/model-subsidies',
        name: 'applicant-model-subsidies',
        meta: { role: 'APPLICANT' },
        component: () => import('../views/admin/policies/AdminModelSubsidiesPage.vue'),
        props: { applicant: true },
      },
      {
        path: '/reviews',
        name: 'review-inbox',
        meta: { reviewWorkspace: true },
        component: () => import('../views/reviews/PolicyReviewInboxPage.vue'),
      },
      {
        path: '/reviews/unavailable',
        name: 'review-link-unavailable',
        meta: { reviewWorkspace: true, menu: 'review-inbox' },
        component: () => import('../views/reviews/ReviewLinkUnavailable.vue'),
      },
      {
        path: '/reviews/:id',
        name: 'review-round',
        meta: { reviewWorkspace: true, menu: 'review-inbox' },
        component: () => import('../views/reviews/PolicyReviewRoundPage.vue'),
        props: (route) => ({
          policyId: route.params.id,
          submissionVersion: Number(route.query.round),
          node: route.query.node || '',
        }),
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
      {
        path: '/admin/policies',
        name: 'admin-policies',
        meta: { role: 'ADMIN' },
        component: () => import('../views/admin/policies/AdminPoliciesPage.vue'),
      },
      {
        path: '/admin/model-subsidies',
        name: 'admin-model-subsidies',
        meta: { role: 'ADMIN' },
        component: () => import('../views/admin/policies/AdminModelSubsidiesPage.vue'),
      },
      {
        path: '/admin/users',
        name: 'admin-users',
        meta: { role: 'ADMIN' },
        component: () => import('../views/admin/users/AdminUsersPage.vue'),
      },
      {
        path: '/admin/review-settings',
        name: 'admin-review-settings',
        meta: { role: 'ADMIN' },
        component: () => import('../views/admin/review-settings/PolicyReviewSettingsPage.vue'),
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
