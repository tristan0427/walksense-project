<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const menuOpen = ref(false)

// ESP32-CAM IP address - replace with your actual IP
const esp32CamUrl = ref('http://172.23.172.172:81/stream')

const logout = () => {
  localStorage.removeItem('token')
  router.push('/login?role=pwd')
}

const goAccount = () => {
  router.push('/account')
}
</script>

<template>
  <div class="min-h-screen bg-gray-100">

    <!-- Header -->
    <header class="bg-yellow-200 shadow-md px-4 py-3 flex items-center justify-between">
      <h1 class="text-lg font-bold text-gray-800">WALKSENSE</h1>

      <button @click="menuOpen = !menuOpen" class="text-gray-700 text-2xl">
        ☰
      </button>
    </header>

    <!-- Slide Menu -->
    <div v-if="menuOpen" class="absolute right-4 mt-2 w-40 bg-white rounded-xl shadow-lg border z-50">
      <button @click="goAccount" class="block w-full text-left px-4 py-3 hover:bg-gray-100">
        Account
      </button>
      <button @click="logout" class="block w-full text-left px-4 py-3 text-red-600 hover:bg-gray-100">
        Logout
      </button>
    </div>

    <!-- Main Content -->
    <div class="p-4 space-y-4">

      <!-- Greeting Card -->
      <div class="bg-yellow-100 rounded-2xl shadow p-4">
        <h2 class="text-xl font-semibold text-gray-800 mb-3">Hi John Doe!</h2>
      </div>

      <!-- Online Status Card -->
      <div class="bg-yellow-100 rounded-2xl shadow p-4 flex items-center">
        <div class="w-10 h-10 bg-gray-800 rounded-full flex items-center justify-center mr-3">
          <svg class="w-6 h-6 text-white" fill="currentColor" viewBox="0 0 20 20">
            <path fill-rule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clip-rule="evenodd"/>
          </svg>
        </div>
        <div>
          <p class="font-medium text-gray-800">You are Online!</p>
        </div>
      </div>

      <!-- ESP32-CAM Live Stream -->
      <div class="bg-white rounded-2xl shadow p-4">
        <h2 class="font-semibold text-gray-800 mb-3">Live Camera Feed</h2>
        <div class="bg-gray-200 rounded-xl overflow-hidden">

          <img
              :src="esp32CamUrl"
              alt="ESP32-CAM Live Stream"
              class="w-full h-auto"
              @error="handleStreamError"
          />
        </div>
        <p class="text-xs text-gray-500 mt-2">
          Stream URL: {{ esp32CamUrl }}
        </p>
      </div>

      <!-- Guardian Info -->
      <div class="bg-white rounded-2xl shadow p-4">
        <h2 class="font-semibold text-gray-800 mb-2">Guardian Information</h2>
        <p class="text-sm text-gray-600">Name: Maria Santos</p>
        <p class="text-sm text-gray-600">Contact: +63 912 345 6789</p>
        <p class="text-green-600 font-medium mt-1">● Connected</p>
      </div>

      <!-- Emergency Button -->
      <div class="bg-white rounded-2xl shadow p-4">
        <button class="w-full bg-red-600 hover:bg-red-700 text-white font-bold py-4 px-6 rounded-xl transition">
          Emergency Alert
        </button>
      </div>

    </div>

  </div>
</template>

<script>
export default {
  methods: {
    handleStreamError(event) {
      console.error('Failed to load camera stream')
      event.target.src = 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300"><rect fill="%23ddd" width="400" height="300"/><text x="50%" y="50%" text-anchor="middle" fill="%23999">Camera Offline</text></svg>'
    }
  }
}
</script>