<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'

const router = useRouter()

// Configure axios
axios.defaults.baseURL = import.meta.env.VITE_API_BASE_URL
axios.defaults.headers.common['Accept'] = 'application/json'
axios.defaults.headers.common['Content-Type'] = 'application/json'

// Wizard State
const currentStep = ref(1)
const loading = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

// Form Data
const email = ref('')
const otp = ref(['', '', '', '', '', ''])
const password = ref('')
const password_confirmation = ref('')
const showPassword = ref(false)

// Timer State
const timeRemaining = ref(300) // 5 minutes
const timerInterval = ref<number | null>(null)
const inputRefs = ref<HTMLInputElement[]>([])
const resending = ref(false)

const formattedTime = computed(() => {
  const minutes = Math.floor(timeRemaining.value / 60)
  const seconds = timeRemaining.value % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
})

const timeColor = computed(() => {
  if (timeRemaining.value > 60) return 'text-green-600'
  if (timeRemaining.value > 30) return 'text-yellow-600'
  return 'text-red-600'
})

const isExpired = computed(() => timeRemaining.value <= 0)

const startTimer = () => {
  if (timerInterval.value) {
    clearInterval(timerInterval.value)
  }
  timeRemaining.value = 300 // 5 minutes
  timerInterval.value = window.setInterval(() => {
    if (timeRemaining.value > 0) {
      timeRemaining.value--
    } else {
      if (timerInterval.value) clearInterval(timerInterval.value)
    }
  }, 1000)
}

const clearMessages = () => {
  errorMessage.value = ''
  successMessage.value = ''
}

// ================= STEP 1: Send OTP ================= //
const handleSendOtp = async () => {
  clearMessages()
  if (!email.value) {
    errorMessage.value = 'Please enter your email address'
    return
  }

  loading.value = true
  try {
    const response = await axios.post('/api/forgot-password/send-otp', { email: email.value })
    successMessage.value = response.data.message
    currentStep.value = 2
    
    // Slight delay to allow DOM to render the OTP inputs before focusing
    setTimeout(() => {
        startTimer()
        clearMessages()
        if (inputRefs.value[0]) inputRefs.value[0].focus()
    }, 100)

  } catch (error: any) {
    if (error.response) {
      errorMessage.value = error.response.data?.message || 'Failed to send OTP.'
    } else {
      errorMessage.value = 'Cannot connect to server. Check your internet connection.'
    }
  } finally {
    loading.value = false
  }
}

// ================= STEP 2: Verify OTP ================= //
const handleInput = (index: number, event: Event) => {
  const input = event.target as HTMLInputElement
  const value = input.value
  if (value && !/^\d$/.test(value)) {
    otp.value[index] = ''
    return
  }
  otp.value[index] = value
  if (value && index < 5) inputRefs.value[index + 1]?.focus()
}

const handleKeyDown = (index: number, event: KeyboardEvent) => {
  if (event.key === 'Backspace' && !otp.value[index] && index > 0) {
    inputRefs.value[index - 1]?.focus()
  }
  if (event.key === 'v' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    navigator.clipboard.readText().then(text => {
      const digits = text.replace(/\D/g, '').slice(0, 6).split('')
      digits.forEach((digit, i) => { if (i < 6) otp.value[i] = digit })
      if (digits.length === 6) inputRefs.value[5]?.focus()
    })
  }
}

const handleVerifyOtp = async () => {
  clearMessages()
  const otpCode = otp.value.join('')
  if (otpCode.length !== 6) {
    errorMessage.value = 'Please enter all 6 digits'
    return
  }
  if (isExpired.value) {
    errorMessage.value = 'Code has expired. Please resend.'
    return
  }

  loading.value = true
  try {
    const response = await axios.post('/api/forgot-password/verify-otp', {
      email: email.value,
      otp: otpCode
    })
    successMessage.value = response.data.message
    if (timerInterval.value) clearInterval(timerInterval.value)
    
    setTimeout(() => {
        currentStep.value = 3
        clearMessages()
    }, 1500)

  } catch (error: any) {
    if (error.response) {
      errorMessage.value = error.response.data?.message || 'Invalid code'
    } else {
      errorMessage.value = 'Cannot connect to server'
    }
  } finally {
    loading.value = false
  }
}

const handleResendOtp = async () => {
  clearMessages()
  resending.value = true
  try {
    await axios.post('/api/forgot-password/send-otp', { email: email.value })
    successMessage.value = 'A new code has been sent.'
    otp.value = ['', '', '', '', '', '']
    inputRefs.value[0]?.focus()
    startTimer()
  } catch (error: any) {
    if (error.response) {
      errorMessage.value = error.response.data?.message || 'Failed to resend code'
    } else {
      errorMessage.value = 'Cannot connect to server'
    }
  } finally {
    resending.value = false
  }
}

// ================= STEP 3: Reset Password ================= //
const handleResetPassword = async () => {
  clearMessages()
  if (password.value.length < 8) {
    errorMessage.value = 'Password must be at least 8 characters long'
    return
  }
  if (password.value !== password_confirmation.value) {
    errorMessage.value = 'Passwords do not match'
    return
  }

  loading.value = true
  try {
    const response = await axios.post('/api/forgot-password/reset', {
      email: email.value,
      password: password.value,
      password_confirmation: password_confirmation.value
    })
    
    successMessage.value = response.data.message + ' Redirecting to login...'
    setTimeout(() => {
      router.push('/')
    }, 2500)

  } catch (error: any) {
    if (error.response) {
      errorMessage.value = error.response.data?.message || 'Failed to reset password'
    } else {
      errorMessage.value = 'Cannot connect to server'
    }
  } finally {
    loading.value = false
  }
}

const goBack = () => {
  if (currentStep.value > 1) {
    currentStep.value--
    clearMessages()
  } else {
    router.push('/')
  }
}
</script>

<template>
  <div class="min-h-screen flex flex-col bg-[#f7d686]">
    <!-- Back Button -->
    <div class="px-4 pt-4 pb-2">
      <button @click="goBack"
              class="flex items-center gap-2 text-gray-700 hover:text-black transition-colors group">
        <svg class="w-6 h-6 transform group-hover:-translate-x-1 transition-transform"
             fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7"/>
        </svg>
        <span class="text-sm font-medium">Back</span>
      </button>
    </div>

    <div class="flex-1 flex flex-col justify-center px-6 pb-8 max-w-md mx-auto w-full">
      <!-- Header Icon -->
      <div class="w-full text-center mb-6">
        <div class="w-20 h-20 bg-black rounded-full flex items-center justify-center mx-auto mb-4">
          <svg v-if="currentStep === 1" class="w-10 h-10 text-[#f7d686]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"/>
          </svg>
          <svg v-else-if="currentStep === 2" class="w-10 h-10 text-[#f7d686]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"/>
          </svg>
          <svg v-else class="w-10 h-10 text-[#f7d686]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/>
          </svg>
        </div>
        
        <h1 class="text-2xl sm:text-3xl font-bold tracking-tight text-black mb-2">
          {{ currentStep === 1 ? 'Forgot Password?' : currentStep === 2 ? 'Verify Email' : 'New Password' }}
        </h1>
        <p class="text-sm text-gray-700">
          {{ currentStep === 1 ? "Enter your email to receive a reset code." : currentStep === 2 ? `We've sent a code to ${email}` : "Create a new, strong password." }}
        </p>
      </div>

      <!-- Messages -->
      <div v-if="successMessage" class="w-full mb-4 p-3 bg-green-100 border-l-4 border-green-500 text-green-700 rounded-r-xl text-sm flex items-start gap-2">
        <svg class="w-4 h-4 text-green-500 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>
        <span class="font-medium">{{ successMessage }}</span>
      </div>
      <div v-if="errorMessage" class="w-full mb-4 p-3 bg-red-100 border-l-4 border-red-500 text-red-700 rounded-r-xl text-sm flex items-start gap-2">
        <svg class="w-4 h-4 text-red-500 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/></svg>
        <span class="font-medium">{{ errorMessage }}</span>
      </div>

      <!-- STEP 1: Email Form -->
      <div v-if="currentStep === 1" class="w-full space-y-4">
        <div>
          <label class="block text-sm font-semibold text-gray-800 mb-1.5 ml-1">Email Address</label>
          <input v-model="email" type="email" placeholder="you@example.com"
                 class="w-full px-4 py-3 bg-white border border-gray-300 rounded-xl focus:ring-2 focus:ring-black focus:border-black outline-none transition-all shadow-sm">
        </div>
        <button @click="handleSendOtp" :disabled="loading"
                class="w-full py-4 rounded-full bg-black text-white text-lg font-semibold shadow-md hover:bg-gray-800 active:scale-[0.98] disabled:opacity-50 transition-all mt-6">
          {{ loading ? 'Sending...' : 'Send OTP' }}
        </button>
      </div>

      <!-- STEP 2: OTP Form -->
      <div v-if="currentStep === 2" class="w-full space-y-6">
        <div class="bg-white rounded-xl p-4 shadow-sm ring-1 ring-gray-200">
          <div class="flex items-center justify-center gap-2">
            <svg class="w-5 h-5 text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"/>
            </svg>
            <span class="text-sm text-gray-600">Expires in:</span>
            <span :class="['text-xl font-bold font-mono', timeColor]">{{ formattedTime }}</span>
          </div>
        </div>

        <div class="flex justify-center gap-2 sm:gap-3">
          <input v-for="(_, index) in otp" :key="index" :ref="el => { if (el) inputRefs[index] = el as HTMLInputElement }"
                 v-model="otp[index]" @input="handleInput(index, $event)" @keydown="handleKeyDown(index, $event)"
                 type="text" inputmode="numeric" maxlength="1"
                 class="w-12 h-14 sm:w-14 sm:h-16 text-center text-2xl font-bold bg-white border-2 border-gray-300 rounded-xl shadow-sm focus:border-black focus:outline-none focus:ring-2 focus:ring-black transition-colors"
          />
        </div>

        <button @click="handleVerifyOtp" :disabled="loading || otp.join('').length !== 6"
                class="w-full py-4 rounded-full bg-black text-white text-lg font-semibold shadow-md hover:bg-gray-800 active:scale-[0.98] disabled:opacity-50 transition-all">
          {{ loading ? 'Verifying...' : 'Verify Code' }}
        </button>
        
        <div class="text-center">
          <button @click="handleResendOtp" :disabled="resending"
                  class="px-5 py-1.5 rounded-full border border-gray-800 text-sm font-semibold text-black hover:bg-black hover:text-white disabled:opacity-50 transition-all">
            {{ resending ? 'Sending...' : 'Resend Code' }}
          </button>
        </div>
      </div>

      <!-- STEP 3: Reset Password Form -->
      <div v-if="currentStep === 3" class="w-full space-y-4">
        <div>
          <label class="block text-sm font-semibold text-gray-800 mb-1.5 ml-1">New Password</label>
          <div class="relative">
            <input v-model="password" :type="showPassword ? 'text' : 'password'" placeholder="••••••••"
                   class="w-full px-4 py-3 bg-white border border-gray-300 rounded-xl focus:ring-2 focus:ring-black focus:border-black outline-none transition-all shadow-sm pr-12">
            <button @click="showPassword = !showPassword" class="absolute right-3 top-1/2 -translate-y-1/2 p-1 text-gray-400 hover:text-gray-600">
              <svg v-if="!showPassword" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/></svg>
              <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"/></svg>
            </button>
          </div>
        </div>

        <div>
          <label class="block text-sm font-semibold text-gray-800 mb-1.5 ml-1">Confirm New Password</label>
          <input v-model="password_confirmation" :type="showPassword ? 'text' : 'password'" placeholder="••••••••"
                 class="w-full px-4 py-3 bg-white border border-gray-300 rounded-xl focus:ring-2 focus:ring-black focus:border-black outline-none transition-all shadow-sm">
        </div>

        <button @click="handleResetPassword" :disabled="loading"
                class="w-full py-4 rounded-full bg-black text-white text-lg font-semibold shadow-md hover:bg-gray-800 active:scale-[0.98] disabled:opacity-50 transition-all mt-6">
          {{ loading ? 'Saving...' : 'Reset Password' }}
        </button>
      </div>
      
    </div>
  </div>
</template>
