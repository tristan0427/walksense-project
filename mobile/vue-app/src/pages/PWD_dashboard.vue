<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router'
import LocationService from "../services/LocationService";


const router = useRouter();
const isTracking = ref(false);
const currentLocation = ref(null);
const error = ref('');
const user = ref(null);
const menuOpen = ref(false)

// ESP32-CAM IP address - replace with your actual IP
const esp32CamUrl = ref('http://172.23.172.172:81/stream')


onMounted(async () => {
  const userStr = localStorage.getItem('user');
  if (userStr) {
    user.value = JSON.parse(userStr);
  }


  await startTracking();
});

onUnmounted(async () => {

})


const startTracking = async () => {
  try{
    error.value = '';

    await LocationService.startTracking();
    isTracking.value = true;

    const position = await LocationService.getCurrentLocation();
    currentLocation.value = position.coords;

    console.log('Tracking started successfully');
  }catch (err){
    error.value = err.message || 'Failed to  start tracking';
    console.error('Tracking error:', err);
  }
};


const stopTracking = async () => {
  try{
    await LocationService.stopTracking();
    isTracking.value = false;
  }catch (err) {
    error.value = err.message || 'Failed to stop tracking'
    console.error('Tracking error:', err);
  }
};

const logout = async () => {

  await stopTracking();

  localStorage.removeItem('token');
  localStorage.removeItem('user');
  await router.push('/login?role=pwd');
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
        <h2 class="text-xl font-semibold text-gray-800 mb-3">
          Hi {{ user?.name || 'John Doe' }}!
        </h2>
      </div>

      <!-- Error Message -->
      <div v-if="error" class="bg-red-100 border border-red-400 text-red-700 rounded-2xl p-4">
        <p class="text-sm font-medium">⚠️ {{ error }}</p>
      </div>

      <!-- GPS Tracking Status Card (NEW) -->
      <div class="bg-yellow-100 rounded-2xl shadow p-4">
        <div class="flex items-center justify-between mb-3">
          <h2 class="font-semibold text-gray-800">GPS Tracking</h2>
          <div
              :class="[
              'w-3 h-3 rounded-full',
              isTracking ? 'bg-green-500 animate-pulse' : 'bg-red-500'
            ]"
          ></div>
        </div>

        <div class="flex items-center">
          <div class="w-10 h-10 bg-gray-800 rounded-full flex items-center justify-center mr-3">
            <svg
                v-if="isTracking"
                class="w-6 h-6 text-green-400"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
            >
              <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"
              />
              <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15 11a3 3 0 11-6 0 3 3 0 016 0z"
              />
            </svg>
            <svg
                v-else
                class="w-6 h-6 text-red-400"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
            >
              <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636"
              />
            </svg>
          </div>
          <div>
            <p class="font-medium text-gray-800">
              {{ isTracking ? 'Tracking Active' : 'Tracking Inactive' }}
            </p>
            <p class="text-xs text-gray-600">
              {{ isTracking ? 'Your guardian can see your location' : 'Location sharing paused' }}
            </p>
          </div>
        </div>

        <!-- Current Location Info (if available) -->
        <div v-if="currentLocation" class="mt-3 pt-3 border-t border-yellow-200">
          <div class="grid grid-cols-2 gap-2 text-xs">
            <div>
              <p class="text-gray-600">Latitude</p>
              <p class="font-mono font-semibold text-gray-800">
                {{ currentLocation.latitude?.toFixed(6) }}
              </p>
            </div>
            <div>
              <p class="text-gray-600">Longitude</p>
              <p class="font-mono font-semibold text-gray-800">
                {{ currentLocation.longitude?.toFixed(6) }}
              </p>
            </div>
            <div>
              <p class="text-gray-600">Accuracy</p>
              <p class="font-semibold text-gray-800">
                ±{{ currentLocation.accuracy?.toFixed(0) }}m
              </p>
            </div>
            <div v-if="currentLocation.speed">
              <p class="text-gray-600">Speed</p>
              <p class="font-semibold text-gray-800">
                {{ (currentLocation.speed * 3.6).toFixed(1) }} km/h
              </p>
            </div>
          </div>
        </div>

        <!-- Restart Button (if tracking failed) -->
        <button
            v-if="!isTracking"
            @click="startTracking"
            class="w-full mt-3 py-2 bg-green-600 hover:bg-green-700 text-white font-medium rounded-xl transition"
        >
          Start Tracking
        </button>
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

      <!-- Safety Info Card (NEW) -->
      <div class="bg-blue-50 border border-blue-200 rounded-2xl p-4">
        <div class="flex items-start">
          <svg class="w-5 h-5 text-blue-600 mr-2 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/>
          </svg>
          <div class="text-sm text-blue-800">
            <p class="font-semibold mb-1">Background Tracking</p>
            <p class="text-xs">Your location is tracked even when this app is closed or your phone is locked. This helps your guardian keep you safe.</p>
          </div>
        </div>
      </div>

      <!-- Emergency Button -->
      <div class="bg-white rounded-2xl shadow p-4">
        <button class="w-full bg-red-600 hover:bg-red-700 text-white font-bold py-4 px-6 rounded-xl transition">
          🚨 Emergency Alert
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