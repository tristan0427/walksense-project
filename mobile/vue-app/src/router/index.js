import { createRouter, createWebHistory } from 'vue-router'
import Home from '../pages/Home.vue'
import Login from '../pages/Login.vue'
import Guardian_dashboard from "../pages/Guardian_dashboard.vue";
import PwdDashboard from '../pages/PWD_dashboard.vue';
import Register from "../pages/Register.vue";
import OTP from "../pages/OTP.vue"

const routes = [
    {
        path: '/',
        name:'Home',
        component: Home
    },

    {
      path: '/register',
      name: 'Registration',
      component: Register
    },

    {
      path: '/login/:type?',
      name: 'Login',
      component: Login
    },

    {
      path: '/otp',
      name: 'OTP',
      component: OTP
    },

    {
        path: '/guardian',
        name: 'GuardianDashboard',
        component: Guardian_dashboard,
        meta: { requiresAuth: true, role: 'guardian' }
    },

    {
        path: '/pwd-dashboard',
        name: 'PwdDashboard',
        component: PwdDashboard,
        meta: { requiresAuth: true, role: 'pwd' }
    },

]

const router = createRouter({
    history: createWebHistory(),
    routes
})

router.beforeEach((to, from, next) => {
    const token = localStorage.getItem('token')
    const user = JSON.parse(localStorage.getItem('user') || '{}')

    if (to.meta.requiresAuth && !token) {
        next('/login')
    } else if (to.meta.role && user.role !== to.meta.role) {
        next('/')
    } else {
        next()
    }
})

export default router
