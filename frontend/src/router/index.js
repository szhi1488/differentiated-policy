import { createRouter, createWebHashHistory } from 'vue-router'
import { parseReviewTarget } from '../utils/review-link'
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
        component: () => import('../views/apply/PolicyApplicationPage.vue'),
      },
      {
        path: '/apply/submissions',
        name: 'submissions',
        component: () => import('../views/apply/MySubmissionsPage.vue'),
      },
      {
        path: '/apply/model-subsidies',
        name: 'applicant-model-subsidies',
        component: () => import('../views/admin/policies/AdminModelSubsidiesPage.vue'),
        props: { applicant: true },
      },
      {
        path: '/reviews/unavailable',
        name: 'review-link-unavailable',
        meta: { reviewWorkspace: true },
        component: () => import('../views/reviews/ReviewLinkUnavailable.vue'),
      },
      {
        path: '/reviews/:id',
        name: 'review-round',
        meta: { reviewWorkspace: true },
        component: () => import('../views/reviews/PolicyReviewRoundPage.vue'),
        props: (route) => ({
          policyId: route.params.id,
          submissionVersion: Number(route.query.round),
        }),
      },
      {
        path: '/admin/catalog/sources',
        name: 'catalog-sources',
        component: () => import('../views/admin/catalog/CatalogSourcesPage.vue'),
        props: (route) => ({ showImport: route.query.import === '1' }),
      },
      {
        path: '/admin/catalog/cleaning',
        name: 'catalog-cleaning',
        component: () => import('../views/admin/catalog/CatalogCleaningPage.vue'),
      },
      {
        path: '/admin/catalog/center',
        name: 'catalog-center',
        component: () => import('../views/admin/catalog/CatalogCenterPage.vue'),
      },
      {
        path: '/admin/policies',
        name: 'admin-policies',
        component: () => import('../views/admin/policies/AdminPoliciesPage.vue'),
        props: (route) => ({ linkedReview: parseReviewTarget(route.fullPath) }),
      },
      {
        path: '/admin/model-subsidies',
        name: 'admin-model-subsidies',
        component: () => import('../views/admin/policies/AdminModelSubsidiesPage.vue'),
      },
      {
        path: '/admin/users',
        name: 'admin-users',
        component: () => import('../views/admin/users/AdminUsersPage.vue'),
      },
      {
        path: '/admin/review-settings',
        name: 'admin-review-settings',
        component: () => import('../views/admin/review-settings/PolicyReviewSettingsPage.vue'),
      },
      {
        path: '/admin/roles',
        redirect: { name: 'roles' },
      },
      {
        path: '/admin/users/roles',
        name: 'roles',
        meta: { menu: 'admin-users' },
        component: () => import('../views/admin/users/RoleManagementPage.vue'),
      },
      {
        path: '/admin/platform-teams',
        name: 'teams',
        component: () => import('../views/admin/users/PlatformTeamsPage.vue'),
      },
      {
        path: '/reviews/history',
        name: 'product-history',
        component: () => import('../views/reviews/ProductHistoryPage.vue'),
      },
      {
        path: '/apply/platform-policies',
        name: 'platform-policies',
        component: () => import('../views/apply/PlatformPoliciesPage.vue'),
      },
      {
        path: '/no-access',
        name: 'no-access',
        component: () => import('../views/auth/NoAccessPage.vue'),
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
