import { createRouter, createWebHistory } from 'vue-router'
import Home from '../pages/Home.vue'
import Login from '../pages/Login.vue'
import Guardian_dashboard from "../pages/Guardian_dashboard.vue";
import PwdDashboard from '../pages/PWD_dashboard.vue';
import Wearablesetup from "@/pages/Wearablesetup.vue";
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

    {
        path: '/wearable-setup',
        name: 'WearableSetup',
        component: Wearablesetup,
        meta: { requiresAuth: true, role: 'pwd' }
    },

]

const router = createRouter({
    history: createWebHistory(),
    routes
})

router.beforeEach((to, from, next) => {
    const token = localStorage.getItem('token')
    const userStr = localStorage.getItem('user')
    const user = userStr ? JSON.parse(userStr) : null


    if (token && user && (to.path === '/' || to.name === 'Login')) {
        if (user.role === 'pwd') return next('/pwd-dashboard')
        if (user.role === 'guardian') return next('/guardian')
    }

    if (to.meta.requiresAuth && !token) {
        return next('/')
    }

    if (to.meta.role && user?.role !== to.meta.role) {
        return next('/')
    }

    next()
})

export default router
