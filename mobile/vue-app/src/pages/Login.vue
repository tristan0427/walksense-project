<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import axios from 'axios'

const router = useRouter()
const route = useRoute()

const userType = ref(route.params.type || 'PWD')
const form = ref({
  email: '',
  password: ''
})

const loading = ref(false)
const errorMessage = ref('')
const showPassword = ref(false)

// Configure axios base URL
axios.defaults.baseURL = 'http://172.23.172.98:8000'
// axios.defaults.baseURL = 'http://192.168.254.125:8000'
axios.defaults.headers.common['Accept'] = 'application/json'
axios.defaults.headers.common['Content-Type'] = 'application/json'

const goBack = () => {
  router.push('/')
}

const handleLogin = async () => {
  errorMessage.value = ''

  if (!form.value.email || !form.value.password) {
    errorMessage.value = 'Please enter both email and password'
    return
  }

  loading.value = true

  try {

    const response = await axios.post('/api/login', {
      email: form.value.email,
      password: form.value.password,
      login_as: (userType.value as string).toLowerCase()
    })

    const token = response.data.token
    const user = response.data.user


    localStorage.setItem('token', token)
    localStorage.setItem('user', JSON.stringify(user))


    axios.defaults.headers.common['Authorization'] = `Bearer ${token}`

    // Route based on user role
    if (user.role === 'guardian') {
      await router.push('/guardian')
    } else if (user.role === 'pwd') {
      await router.push('/pwd-dashboard')
    } else {
      errorMessage.value = 'Invalid user role'
    }

  } catch (error: any) {
    console.error('Login error:', error)

    if (error.response) {
      errorMessage.value = error.response.data?.message || 'Login failed'
    } else if (error.request) {
      errorMessage.value = 'Cannot connect to server. Check your internet connection.'
    } else {
      errorMessage.value = 'An unexpected error occurred'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex flex-col bg-[#f7d686] px-6 py-6">


    <button
        @click="goBack"
        class="self-start mb-6 flex items-center gap-2 text-gray-700 hover:text-black transition-colors group"
    >
      <svg
          class="w-6 h-6 transform group-hover:-translate-x-1 transition-transform"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
      >
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7"/>
      </svg>
      <span class="font-medium">Back</span>
    </button>

    <!-- Main Content -->
    <div class="flex-1 flex flex-col items-center justify-center">
      <!-- Header -->
      <div class="w-full max-w-md text-center mb-8">
        <h1 class="text-2xl font-bold mb-2">
          Logging in as {{ userType }}
        </h1>
        <h2 class="text-3xl font-bold mb-4">
          with <span class="text-black">WALKSENSE!</span>
        </h2>
        <p class="text-sm text-gray-700">
          <template v-if="userType === 'PWD'">
            Enter the <strong>Guardian's email and password</strong> to access the PWD dashboard.
          </template>
          <template v-else>
            Hi there guardian, enter your email and password to login your account.
          </template>
        </p>
      </div>

      <div v-if="errorMessage" class="w-full max-w-md mb-4 p-3 bg-red-100 border border-red-400 text-red-700 rounded-lg text-sm">
        {{ errorMessage }}
      </div>


      <form @submit.prevent="handleLogin" class="w-full max-w-md space-y-4">
        <!-- Email Input -->
        <div class="relative">
          <div class="absolute inset-y-0 left-0 flex items-center pl-4 pointer-events-none">
            <svg class="w-5 h-5 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"/>
            </svg>
          </div>
          <input
              v-model="form.email"
              type="email"
              placeholder="Guardian email"
              class="w-full pl-12 pr-4 py-4 rounded-full bg-white/90 focus:outline-none focus:ring-2 focus:ring-black"
              required
          />
        </div>

        <!-- Password Input -->
        <div class="relative">
          <div class="absolute inset-y-0 left-0 flex items-center pl-4 pointer-events-none">
            <svg class="w-5 h-5 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"/>
            </svg>
          </div>
          <input
              v-model="form.password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="Guardian password"
              class="w-full pl-12 pr-12 py-4 rounded-full bg-white/90 focus:outline-none focus:ring-2 focus:ring-black"
              required
          />
          <button
              type="button"
              @click="showPassword = !showPassword"
              class="absolute inset-y-0 right-0 flex items-center pr-4"
          >
            <svg v-if="!showPassword" class="w-5 h-5 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
            </svg>
            <svg v-else class="w-5 h-5 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"/>
            </svg>
          </button>
        </div>

        <!-- Login Button -->
        <button
            type="submit"
            :disabled="loading"
            class="w-full py-4 rounded-full bg-black text-white text-lg font-semibold hover:bg-gray-800 disabled:opacity-50 disabled:cursor-not-allowed transition-all mt-24"
        >
          {{ loading ? 'Logging in...' : 'Login' }}
        </button>
      </form>

      <!-- Register Link -->
      <p class="mt-8 text-sm">
        don't have account yet?
        <router-link to="/register" class="text-blue-600 underline">
          Register here
        </router-link>
      </p>
    </div>
  </div>
</template>