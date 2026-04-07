<script setup lang="ts">
import {ref, onMounted, computed} from 'vue'
import { useRouter, useRoute } from 'vue-router'
import axios from 'axios'

const router = useRouter()
const route = useRoute()

// Get email from route params or query
const email = ref(route.query.email as string || '')
const otp = ref(['', '', '', '', '', ''])
const loading = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const resending = ref(false)
const inputRefs = ref<HTMLInputElement[]>([])

const timeRemaining = ref(120)
const timerInterval = ref<number | null>(null)

// Configure axios
axios.defaults.baseURL = import.meta.env.VITE_API_BASE_URL
axios.defaults.headers.common['Accept'] = 'application/json'
axios.defaults.headers.common['Content-Type'] = 'application/json'

const formattedTime = computed(() => {
  const minutes = Math.floor(timeRemaining.value /60)
  const seconds = timeRemaining.value %60

  return `${minutes}: ${seconds.toString().padStart(2, '0')}`
})

const timeColor = computed(()=>{
  if (timeRemaining.value > 60) return 'text-green-600'
  if (timeRemaining.value > 30) return 'text-yellow-600'
  return 'text-red-600'
})

const isExpired = computed(() => timeRemaining.value <=0)

const startTimer = () => {
  if(timerInterval.value){
    clearInterval(timerInterval.value)
  }

  timeRemaining.value = 120

  timerInterval.value = window.setInterval(()=>{
    if(timeRemaining.value > 0){
      timeRemaining.value--
    }else {
      if(timerInterval.value){
        clearInterval(timerInterval.value)
      }
    }
  },1000)
}


onMounted(() => {
  if (!email.value) {
    errorMessage.value = 'Email not provided. Please register again.'
    setTimeout(() => router.push('/register'), 3000)
    return
  }

  // Focus first input
  if (inputRefs.value[0]) {
    inputRefs.value[0].focus()
  }

  startTimer()
})

const handleInput = (index: number, event: Event) => {
  const input = event.target as HTMLInputElement
  const value = input.value

  // Only allow numbers
  if (value && !/^\d$/.test(value)) {
    otp.value[index] = ''
    return
  }

  otp.value[index] = value

  // Auto-focus next input
  if (value && index < 5) {
    inputRefs.value[index + 1]?.focus()
  }
}

const handleKeyDown = (index: number, event: KeyboardEvent) => {
  // Handle backspace
  if (event.key === 'Backspace' && !otp.value[index] && index > 0) {
    inputRefs.value[index - 1]?.focus()
  }

  // Handle paste
  if (event.key === 'v' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    navigator.clipboard.readText().then(text => {
      const digits = text.replace(/\D/g, '').slice(0, 6).split('')
      digits.forEach((digit, i) => {
        if (i < 6) {
          otp.value[i] = digit
        }
      })
      if (digits.length === 6) {
        inputRefs.value[5]?.focus()
      }
    })
  }
}

const handleVerify = async () => {
  errorMessage.value = ''
  successMessage.value = ''

  const otpCode = otp.value.join('')

  if (otpCode.length !== 6) {
    errorMessage.value = 'Please enter all 6 digits'
    return
  }

  if(isExpired.value){
    errorMessage.value = 'Code has expired. Please request a new one.'
  }

  loading.value = true

  try {
    const response = await axios.post('/api/verify-otp', {
      email: email.value,
      otp: otpCode
    })

    successMessage.value = response.data.message


    setTimeout(() => {
      router.push('/')
    }, 2000)

  } catch (error: any) {
    console.error('Verification error:', error)

    if (error.response) {
      errorMessage.value = error.response.data?.message || 'Verification failed'
    } else if (error.request) {
      errorMessage.value = 'Cannot connect to server. Check your internet connection.'
    } else {
      errorMessage.value = 'An unexpected error occurred'
    }
  } finally {
    loading.value = false
  }
}

const handleResend = async () => {
  errorMessage.value = ''
  successMessage.value = ''
  resending.value = true

  try {
    const response = await axios.post('/api/resend-otp', {
      email: email.value
    })

    successMessage.value = response.data.message

    // Clear OTP inputs
    otp.value = ['', '', '', '', '', '']
    inputRefs.value[0]?.focus()

    startTimer()

  } catch (error: any) {
    console.error('Resend error:', error)

    if (error.response) {
      errorMessage.value = error.response.data?.message || 'Failed to resend code'
    } else {
      errorMessage.value = 'Cannot connect to server'
    }
  } finally {
    resending.value = false
  }
}

const goBack = () => {
  router.push('/register')
}
</script>

<template>
  <div class="min-h-screen flex flex-col bg-[#f7d686]">
    <!-- Back -->
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

    <div class="flex-1 flex flex-col justify-center px-6 pb-8">
      <!-- Header -->
      <div class="w-full text-center mb-8">
        <div class="mb-6">
          <div class="w-20 h-20 bg-black rounded-full flex items-center justify-center mx-auto">
            <svg class="w-10 h-10 text-[#f7d686]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"/>
            </svg>
          </div>
        </div>

        <h1 class="text-2xl sm:text-3xl font-bold tracking-tight text-black mb-3">
          Verify Your Email
        </h1>
        <p class="text-sm text-gray-700 mb-1">We've sent a verification code to</p>
        <p class="text-sm font-semibold text-black break-all">{{ email }}</p>
      </div>

      <!-- Timer -->
      <div class="w-full mb-6">
        <div class="bg-white rounded-xl p-4 shadow-md ring-1 ring-gray-200">
          <div class="flex items-center justify-center gap-2">
            <svg class="w-5 h-5 text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"/>
            </svg>
            <span class="text-sm text-gray-600">Code expires in:</span>
            <span :class="['text-xl font-bold font-mono', timeColor]">{{ formattedTime }}</span>
          </div>
          <div v-if="isExpired" class="mt-2 text-center">
            <p class="text-xs text-red-600 font-semibold">Code has expired! Please request a new one.</p>
          </div>
        </div>
      </div>

      <!-- Success -->
      <div v-if="successMessage" class="w-full mb-4 p-3 bg-green-100 border-l-4 border-green-500 text-green-700 rounded-r-xl text-sm flex items-start gap-2">
        <svg class="w-4 h-4 text-green-500 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/>
        </svg>
        <span class="font-medium">{{ successMessage }}</span>
      </div>

      <!-- Error -->
      <div v-if="errorMessage" class="w-full mb-4 p-3 bg-red-100 border-l-4 border-red-500 text-red-700 rounded-r-xl text-sm flex items-start gap-2">
        <svg class="w-4 h-4 text-red-500 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
        </svg>
        <span class="font-medium">{{ errorMessage }}</span>
      </div>

      <!-- OTP Inputs -->
      <div class="w-full mb-8">
        <div class="flex justify-center gap-2 sm:gap-3">
          <input
              v-for="(_, index) in otp"
              :key="index"
              :ref="el => { if (el) inputRefs[index] = el as HTMLInputElement }"
              v-model="otp[index]"
              @input="handleInput(index, $event)"
              @keydown="handleKeyDown(index, $event)"
              type="text"
              inputmode="numeric"
              maxlength="1"
              class="w-12 h-14 sm:w-14 sm:h-16 text-center text-2xl font-bold bg-white border-2 border-gray-300 rounded-xl shadow-sm focus:border-black focus:outline-none focus:ring-2 focus:ring-black focus:ring-offset-1 transition-colors"
          />
        </div>
      </div>

      <!-- Verify -->
      <button @click="handleVerify" :disabled="loading || otp.join('').length !== 6"
              class="w-full py-4 rounded-full bg-black text-white text-base sm:text-lg font-semibold shadow-md hover:bg-gray-800 active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed transition-all mb-8">
        {{ loading ? 'Verifying...' : 'Verify Code' }}
      </button>

      <!-- Resend -->
      <div class="w-full text-center">
        <p class="text-xs text-gray-600 mb-3">Didn't receive the code?</p>
        <button @click="handleResend" :disabled="resending"
                class="px-5 py-1.5 rounded-full border border-gray-800 text-sm font-semibold text-black hover:bg-black hover:text-white disabled:opacity-50 disabled:cursor-not-allowed transition-all">
          {{ resending ? 'Sending...' : 'Resend Code' }}
        </button>
      </div>
    </div>
  </div>
</template>