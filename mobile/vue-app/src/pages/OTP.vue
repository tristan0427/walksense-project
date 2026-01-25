<script setup lang="ts">
import { ref, onMounted } from 'vue'
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

// Configure axios
axios.defaults.baseURL = 'http://192.168.1.7:8000'
axios.defaults.headers.common['Accept'] = 'application/json'
axios.defaults.headers.common['Content-Type'] = 'application/json'

// Auto-focus first input
const inputRefs = ref<HTMLInputElement[]>([])

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

  loading.value = true

  try {
    const response = await axios.post('/api/verify-otp', {
      email: email.value,
      otp: otpCode
    })

    successMessage.value = response.data.message

    // Redirect to login after 2 seconds
    setTimeout(() => {
      router.push('/login/guardian')
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
  <div class="min-h-screen flex flex-col bg-[#f7d686] px-6 py-6">
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
    <div class="flex-1 flex flex-col items-center justify-center">
      <!-- Header -->
      <div class="w-full max-w-md text-center mb-8">
        <div class="mb-6">
          <div class="w-20 h-20 bg-black rounded-full flex items-center justify-center mx-auto mb-4">
            <svg class="w-10 h-10 text-[#f7d686]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"/>
            </svg>
          </div>
        </div>

        <h1 class="text-3xl font-bold mb-2">
          Verify Your Email
        </h1>
        <p class="text-sm text-gray-700 mb-2">
          We've sent a verification code to
        </p>
        <p class="text-sm font-semibold text-black">
          {{ email }}
        </p>
      </div>

      <!-- Success Message -->
      <div v-if="successMessage" class="w-full max-w-md mb-4 p-3 bg-green-100 border border-green-400 text-green-700 rounded-lg text-sm text-center">
        {{ successMessage }}
      </div>

      <!-- Error Message -->
      <div v-if="errorMessage" class="w-full max-w-md mb-4 p-3 bg-red-100 border border-red-400 text-red-700 rounded-lg text-sm text-center">
        {{ errorMessage }}
      </div>

      <!-- OTP Input -->
      <div class="w-full max-w-md mb-8">
        <div class="flex justify-center gap-3">
          <input
              v-for="(digit, index) in otp"
              :key="index"
              :ref="el => { if (el) inputRefs[index] = el as HTMLInputElement }"
              v-model="otp[index]"
              @input="handleInput(index, $event)"
              @keydown="handleKeyDown(index, $event)"
              type="text"
              inputmode="numeric"
              maxlength="1"
              class="w-14 h-14 text-center text-2xl font-bold bg-white border-2 border-gray-300 rounded-lg focus:border-black focus:outline-none transition-colors"
          />
        </div>
      </div>


      <button
          @click="handleVerify"
          :disabled="loading || otp.join('').length !== 6"
          class="w-full max-w-md py-4 rounded-full bg-black text-white text-lg font-semibold hover:bg-gray-800 disabled:opacity-50 disabled:cursor-not-allowed transition-all mb-6"
      >
        {{ loading ? 'Verifying...' : 'Verify Code' }}
      </button>


      <div class="text-center">
        <p class="text-sm text-gray-700 mb-2">
          Didn't receive the code?
        </p>
        <button
            @click="handleResend"
            :disabled="resending"
            class="text-sm font-semibold text-black underline hover:no-underline disabled:opacity-50"
        >
          {{ resending ? 'Sending...' : 'Resend Code' }}
        </button>
      </div>
    </div>
  </div>
</template>