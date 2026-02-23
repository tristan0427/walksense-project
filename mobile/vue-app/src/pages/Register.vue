<script setup lang="ts">
import {computed, ref} from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'

const router = useRouter()

// Form data
const guardianForm = ref({
  firstname: '',
  lastname: '',
  middle_initial: '',
  address: '',
  email: '',
  password: '',
  password_confirmation: ''
})

const pwdForm = ref({
  firstname: '',
  lastname: '',
  middle_initial: ''
})

const loading = ref(false)
const errorMessage = ref('')
const showPassword = ref(false)
const showConfirmPassword = ref(false)

// Configure axios base URL
// axios.defaults.baseURL = 'http://172.23.172.98:8000'
// axios.defaults.baseURL = 'http://192.168.1.7:8000'
axios.defaults.baseURL = import.meta.env.VITE_API_BASE_URL
axios.defaults.headers.common['Accept'] = 'application/json'
axios.defaults.headers.common['Content-Type'] = 'application/json'


const passwordChecks = computed(() => ({
  minLength: guardianForm.value.password.length >= 8,
  hasUppercase: /[A-Z]/.test(guardianForm.value.password),
  hasLowercase: /[a-z]/.test(guardianForm.value.password),
  hasNumber: /[0-9]/.test(guardianForm.value.password),
  hasSpecial: /[!@#$%^&*(),.?":{}|<>]/.test(guardianForm.value.password),
}))


const isPasswordValid = computed(() => {
  return Object.values(passwordChecks.value).every(check => check)
})

const passwordStrength = computed(() => {
  const checks = Object.values(passwordChecks.value).filter(v => v).length
  if (checks === 0) return { label: '', color: '' }
  if (checks <= 2) return { label: 'Weak', color: 'text-red-600' }
  if (checks <= 3) return { label: 'Fair', color: 'text-yellow-600' }
  if (checks <= 4) return { label: 'Good', color: 'text-blue-600' }
  return { label: 'Strong', color: 'text-green-600' }
})

const passwordsMatch = computed(() => {
  if(!guardianForm.value.password_confirmation) return null
  return guardianForm.value.password === guardianForm.value.password_confirmation
})

const goBack = () => {
  router.push('/')
}

const validateForm = () => {
  // Guardian validation
  if (!guardianForm.value.firstname || !guardianForm.value.lastname ||
      !guardianForm.value.email || !guardianForm.value.password) {
    errorMessage.value = 'Please fill in all required Guardian fields'
    return false
  }

  // Password requirements check
  if (!isPasswordValid.value) {
    errorMessage.value = 'Password must meet all security requirements'
    return false
  }

  // Password match validation
  if (!passwordsMatch.value) {
    errorMessage.value = 'Passwords do not match'
    return false
  }

  // PWD validation
  if (!pwdForm.value.firstname || !pwdForm.value.lastname) {
    errorMessage.value = 'Please fill in all required Visually Impaired fields'
    return false
  }

  return true
}

const handleRegister = async () => {
  errorMessage.value = ''

  if (!validateForm()) {
    return
  }

  loading.value = true

  try {
    const response = await axios.post('/api/register', {
      guardian: {
        firstname: guardianForm.value.firstname,
        lastname: guardianForm.value.lastname,
        middle_initial: guardianForm.value.middle_initial,
        address: guardianForm.value.address,
        email: guardianForm.value.email,
        password: guardianForm.value.password,
        password_confirmation: guardianForm.value.password_confirmation
      },
      pwd: {
        firstname: pwdForm.value.firstname,
        lastname: pwdForm.value.lastname,
        middle_initial: pwdForm.value.middle_initial
      }
    })

    await router.push({
      name: 'OTP',
      query: {
        email: guardianForm.value.email
      }
    })


  } catch (error: any) {
    console.error('Registration error:', error)

    if (error.response) {
      errorMessage.value = error.response.data?.message || 'Registration failed'
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
  <div class="min-h-screen flex flex-col bg-[#f7d686] px-6 py-6 overflow-y-auto">
    <!-- Back Button -->
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
    <div class="flex-1 flex flex-col pb-8">
      <!-- Header -->
      <div class="w-full max-w-md mx-auto mb-6">
        <h1 class="text-3xl font-bold mb-2">
          Welcome to
        </h1>
        <h2 class="text-3xl font-bold mb-4">
          <span class="text-black">WALKSENSE!</span>
        </h2>
      </div>

      <!-- Error Message -->
      <div v-if="errorMessage" class="w-full max-w-md mx-auto mb-4 p-3 bg-red-100 border border-red-400 text-red-700 rounded-lg text-sm">
        {{ errorMessage }}
      </div>

      <!-- Registration Form -->
      <form @submit.prevent="handleRegister" class="w-full max-w-md mx-auto space-y-6">

        <!-- Guardian Section -->
        <div class="space-y-4">
          <h3 class="text-lg font-semibold">For the Guardian</h3>

          <div class="space-y-1">
            <input
                v-model="guardianForm.firstname"
                type="text"
                placeholder="first name"
                class="w-full px-4 py-3 bg-transparent border-b-2 border-gray-800 focus:outline-none focus:border-black placeholder:text-gray-700 text-sm"
                required
            />
          </div>

          <div class="space-y-1">
            <input
                v-model="guardianForm.lastname"
                type="text"
                placeholder="last name"
                class="w-full px-4 py-3 bg-transparent border-b-2 border-gray-800 focus:outline-none focus:border-black placeholder:text-gray-700 text-sm"
                required
            />
          </div>

          <div class="space-y-1">
            <input
                v-model="guardianForm.middle_initial"
                type="text"
                maxlength="1"
                placeholder="middle initial"
                class="w-full px-4 py-3 bg-transparent border-b-2 border-gray-800 focus:outline-none focus:border-black placeholder:text-gray-700 text-sm"
            />
          </div>

          <div class="space-y-1">
            <input
                v-model="guardianForm.address"
                type="text"
                placeholder="address"
                class="w-full px-4 py-3 bg-transparent border-b-2 border-gray-800 focus:outline-none focus:border-black placeholder:text-gray-700 text-sm"
            />
          </div>

          <div class="space-y-1">
            <input
                v-model="guardianForm.email"
                type="email"
                placeholder="email"
                class="w-full px-4 py-3 bg-transparent border-b-2 border-gray-800 focus:outline-none focus:border-black placeholder:text-gray-700 text-sm"
                required
            />
          </div>

          <div class="space-y-1 relative">
            <input
                v-model="guardianForm.password"
                :type="showPassword ? 'text' : 'password'"
                placeholder="password"
                class="w-full px-4 py-3 pr-10 bg-transparent border-b-2 border-gray-800 focus:outline-none focus:border-black placeholder:text-gray-700 text-sm"
                required
            />
            <button
                type="button"
                @click="showPassword = !showPassword"
                class="absolute right-2 top-1/2 -translate-y-1/2"
            >
              <svg v-if="!showPassword" class="w-5 h-5 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
              </svg>
              <svg v-else class="w-5 h-5 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"/>
              </svg>
            </button>
          </div>

          <div v-if="guardianForm.password" class="mt-3 p-4 bg-gray-50 rounded-lg border-2 border-gray-200 space-y-2">
            <div class="flex items-center justify-between mb-2">
              <span class="text-xs font-semibold text-gray-700">Password Strength:</span>
              <span :class="['text-xs font-bold', passwordStrength.color]">
                {{ passwordStrength.label }}
              </span>
            </div>

            <div class="space-y-1.5">
              <div class="flex items-center gap-2">
                <svg :class="['w-4 h-4 shrink-0', passwordChecks.minLength ? 'text-green-600' : 'text-gray-400']" fill="currentColor" viewBox="0 0 20 20">
                  <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/>
                </svg>
                <span :class="['text-xs', passwordChecks.minLength ? 'text-green-600 font-medium' : 'text-gray-600']">
                  At least 8 characters
                </span>
              </div>

              <div class="flex items-center gap-2">
                <svg :class="['w-4 h-4 shrink-0', passwordChecks.hasUppercase ? 'text-green-600' : 'text-gray-400']" fill="currentColor" viewBox="0 0 20 20">
                  <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/>
                </svg>
                <span :class="['text-xs', passwordChecks.hasUppercase ? 'text-green-600 font-medium' : 'text-gray-600']">
                  One uppercase letter (A-Z)
                </span>
              </div>

              <div class="flex items-center gap-2">
                <svg :class="['w-4 h-4 shrink-0', passwordChecks.hasLowercase ? 'text-green-600' : 'text-gray-400']" fill="currentColor" viewBox="0 0 20 20">
                  <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/>
                </svg>
                <span :class="['text-xs', passwordChecks.hasLowercase ? 'text-green-600 font-medium' : 'text-gray-600']">
                  One lowercase letter (a-z)
                </span>
              </div>

              <div class="flex items-center gap-2">
                <svg :class="['w-4 h-4 shrink-0', passwordChecks.hasNumber ? 'text-green-600' : 'text-gray-400']" fill="currentColor" viewBox="0 0 20 20">
                  <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/>
                </svg>
                <span :class="['text-xs', passwordChecks.hasNumber ? 'text-green-600 font-medium' : 'text-gray-600']">
                  One number (0-9)
                </span>
              </div>

              <div class="flex items-center gap-2">
                <svg :class="['w-4 h-4 shrink-0', passwordChecks.hasSpecial ? 'text-green-600' : 'text-gray-400']" fill="currentColor" viewBox="0 0 20 20">
                  <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/>
                </svg>
                <span :class="['text-xs', passwordChecks.hasSpecial ? 'text-green-600 font-medium' : 'text-gray-600']">
                  One special character (!@#$%^&*)
                </span>
              </div>
            </div>
          </div>

          <div class="space-y-1 relative">
            <input
                v-model="guardianForm.password_confirmation"
                :type="showConfirmPassword ? 'text' : 'password'"
                placeholder="confirm password"
                class="w-full px-4 py-3 pr-10 bg-transparent border-b-2 border-gray-800 focus:outline-none focus:border-black placeholder:text-gray-700 text-sm"
                required
            />
            <button
                type="button"
                @click="showConfirmPassword = !showConfirmPassword"
                class="absolute right-2 top-1/2 -translate-y-1/2"
            >
              <svg v-if="!showConfirmPassword" class="w-5 h-5 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
              </svg>
              <svg v-else class="w-5 h-5 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"/>
              </svg>
            </button>
          </div>

          <div v-if="guardianForm.password_confirmation" class="mt-2">
            <div v-if="passwordsMatch" class="flex items-center gap-2 text-green-600">
              <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/>
              </svg>
              <span class="text-sm font-medium">Passwords match</span>
            </div>
            <div v-else class="flex items-center gap-2 text-red-600">
              <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"/>
              </svg>
              <span class="text-sm font-medium">Passwords do not match</span>
            </div>
          </div>
        </div>

        <!-- PWD Section -->
        <div class="space-y-4 pt-6">
          <h3 class="text-lg font-semibold">For the Visually Impaired</h3>

          <div class="space-y-1">
            <input
                v-model="pwdForm.firstname"
                type="text"
                placeholder="first name"
                class="w-full px-4 py-3 bg-transparent border-b-2 border-gray-800 focus:outline-none focus:border-black placeholder:text-gray-700 text-sm"
                required
            />
          </div>

          <div class="space-y-1">
            <input
                v-model="pwdForm.lastname"
                type="text"
                placeholder="last name"
                class="w-full px-4 py-3 bg-transparent border-b-2 border-gray-800 focus:outline-none focus:border-black placeholder:text-gray-700 text-sm"
                required
            />
          </div>

          <div class="space-y-1">
            <input
                v-model="pwdForm.middle_initial"
                type="text"
                maxlength="1"
                placeholder="middle initial"
                class="w-full px-4 py-3 bg-transparent border-b-2 border-gray-800 focus:outline-none focus:border-black placeholder:text-gray-700 text-sm"
            />
          </div>
        </div>

        <!-- Register Button -->
        <button
            type="submit"
            :disabled="loading"
            class="w-full py-4 rounded-full bg-black text-white text-lg font-semibold hover:bg-gray-800 disabled:opacity-50 disabled:cursor-not-allowed transition-all mt-8"
        >
          {{ loading ? 'Registering...' : 'Register' }}
        </button>
      </form>

      <!-- Login Link -->
      <div class="w-full max-w-md mx-auto mt-6 text-center">
        <p class="text-sm">
          already have an account?
          <router-link to="/login" class="text-blue-600 underline font-medium">
            Login here
          </router-link>
        </p>
      </div>
    </div>
  </div>
</template>