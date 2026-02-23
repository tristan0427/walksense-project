<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router'
import { TextToSpeech } from '@capacitor-community/text-to-speech';
import LocationService from "../services/LocationService";
import ObjectDetection from "../types/ObjectDetection";

const router = useRouter();
const isTracking = ref(false);
const currentLocation = ref(null);
const error = ref('');
const user = ref(null);
const guardian = ref(null)
const menuOpen = ref(false)
const showLogoutConfirm = ref(false)

const esp32Connected = ref(false);
const esp32Status = ref('Not Connected');
const nearestObject = ref(null);
const detectionInterval = ref(null);
const lastSpokenTime = ref(0);
const SPEAK_DELAY = 4000;

onMounted(async () => {
  console.log('PWD Dashboard mounted');

  ObjectDetection.addListener('streamConnected', (data) => {
    console.log('Stream connected event:', data);
    esp32Connected.value = true;
    esp32Status.value = 'Connected';
  });

  ObjectDetection.addListener('streamError', (data) => {
    console.error('Stream error event:', data);
    esp32Connected.value = false;
    esp32Status.value = 'Error: ' + data.error;
  });

  //Model
  try{
    await ObjectDetection.loadModel();
    console.log('Model loaded');
  }catch (err){
    console.error('Model load failed:', err);
  }

  await connectToESP32();

  startDetection();


  const userStr = localStorage.getItem('user');
  if (userStr) {
    user.value = JSON.parse(userStr);
  }

  const guardianStr = localStorage.getItem('guardian');
  if (guardianStr) {
    guardian.value = JSON.parse(guardianStr);
    console.log('Guardian info loaded:', guardian.value);
  }else {
    console.warn('No guardian information found in localStorage');
  }

  console.log('PWD Dashboard mounted - starting tracking automatically...');
  await startTracking();
});


onUnmounted(async () => {
  stopDetection();
  await ObjectDetection.stopESP32Stream();
  ObjectDetection.removeAllListeners();
})

//YOLO Model

const connectToESP32 = async () => {
  try{
    const result = await ObjectDetection.startESP32Stream();
    if(result.success){
      esp32Connected.value = true;
      console.log('ESP32 connected');
    }
  }catch (err){
    console.error('ESP32 Connection failed:', err);
  }
};

const startDetection = () => {
  detectionInterval.value = setInterval(async () => {
    try{
      const result = await ObjectDetection.detectFromStream({
        confidence: 0.6
      });

      console.log('Detection result:', result);

      if (result.nearest){
        nearestObject.value = result.nearest;
        console.log('Nearest object found:', result.nearest);

        //TTS
        const now = Date.now();
        if(now - lastSpokenTime.value >= SPEAK_DELAY){
          const msg = `${result.nearest.class} ${result.nearest.distance} on the ${result.nearest.direction}`;
          console.log('Attempting to speak:', msg); // ADD THIS

          try {
            await TextToSpeech.speak({
              text: msg,
              lang: 'en-US',
              rate: 1.0,
              pitch: 1.0,
              volume: 1.0
            });
            console.log('Speech completed'); // ADD THIS
            lastSpokenTime.value = now;
          } catch (ttsError) {
            console.error('TTS error:', ttsError); // ADD THIS
          }
        }
      } else {
        console.log('No objects detected in this frame'); // ADD THIS
      }
    }catch (err) {
      console.error('Detection error:', err);
    }
  }, 500);
};

const stopDetection = () => {
  if (detectionInterval.value){
    clearInterval(detectionInterval.value);
  }
}

//GPS
const startTracking = async () => {
  try {
    error.value = '';
    console.log('Starting location tracking...');

    await LocationService.startTracking();
    isTracking.value = true;

    const position = await LocationService.getCurrentLocation();
    currentLocation.value = position.coords;

    console.log('Tracking started successfully');
    console.log('Current location:', currentLocation.value);
  } catch (err) {
    error.value = err.message || 'Failed to start tracking';
    console.error('Tracking error:', err);
    isTracking.value = false;

    // Log detailed error
    console.error('Error details:', {
      message: error.value,
      error: err
    });
  }
};

const stopTracking = async () => {
  try {
    await LocationService.stopTracking();
    isTracking.value = false;
  } catch (err) {
    error.value = err.message || 'Failed to stop tracking'
    console.error('Tracking error:', err);
  }
};

const logout = () => {
  showLogoutConfirm.value = true
}

const cancelLogout = () => {
  showLogoutConfirm.value = false
}

const confirmLogout = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  localStorage.removeItem('guardian')
  showLogoutConfirm.value = false
  router.push('/')
}

const goAccount = () => {
  router.push('/account')
}
</script>

<template>
  <div class="min-h-screen bg-gray-100 flex flex-col">

    <!-- Header -->
    <header class="bg-[#f7d686] shadow-md px-4 py-3 flex items-center justify-between">
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
      <div v-if="error" class="bg-red-100 border-4 border-red-500 text-red-700 rounded-2xl p-4 animate-pulse">
        <p class="text-base font-bold">⚠️ TRACKING ERROR</p>
        <p class="text-sm font-medium mt-2">{{ error }}</p>
        <p class="text-xs text-red-600 mt-2">Please check your location permissions and GPS settings.</p>
      </div>

      <!-- GPS Tracking Status Card -->
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

        <!-- Current Location Info -->
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

      <!-- ESP32 Status -->
      <div :class="[
      'p-4 rounded-xl mb-4',
      esp32Connected ? 'bg-green-100' : 'bg-red-100'
    ]">
        <div class="flex items-center gap-3">
          <div :class="[
          'w-4 h-4 rounded-full',
          esp32Connected ? 'bg-green-500 animate-pulse' : 'bg-red-500'
        ]"></div>
          <div>
            <p class="font-semibold">ESP32-CAM</p>
            <p class="text-sm">{{ esp32Connected ? 'Connected' : 'Not Connected' }}</p>
          </div>
        </div>
      </div>

      <!-- Detection -->
      <div class="bg-white rounded-xl p-4">
        <h2 class="font-bold mb-3">Nearest Obstacle</h2>

        <div v-if="nearestObject" class="space-y-2">
          <p><strong>Object:</strong> {{ nearestObject.class }}</p>
          <p><strong>Distance:</strong> {{ nearestObject.distance }}</p>
          <p><strong>Direction:</strong> {{ nearestObject.direction }}</p>
          <p><strong>Confidence:</strong> {{ (nearestObject.confidence * 100).toFixed(0) }}%</p>
        </div>

        <div v-else class="text-gray-500 text-center py-4">
          <p>No objects detected</p>
        </div>
      </div>

      <!-- Guardian Info -->
      <div class="bg-white rounded-2xl shadow p-4">
        <h2 class="font-semibold text-gray-800 mb-3 flex items-center gap-2">
          <svg class="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"/>
          </svg>
          Guardian Information
        </h2>

        <!-- If guardian data exists -->
        <div v-if="guardian">
          <div class="space-y-2">
            <div class="flex items-start">
              <svg class="w-4 h-4 text-gray-500 mr-2 mt-0.5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"/>
              </svg>
              <div>
                <p class="text-xs text-gray-500">Name</p>
                <p class="text-sm font-semibold text-gray-800">{{ guardian.name }}</p>
              </div>
            </div>

            <div class="flex items-start">
              <svg class="w-4 h-4 text-gray-500 mr-2 mt-0.5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"/>
              </svg>
              <div>
                <p class="text-xs text-gray-500">Email</p>
                <p class="text-sm font-medium text-gray-800 break-all">{{ guardian.email }}</p>
              </div>
            </div>
          </div>

          <div class="mt-3 pt-3 border-t border-gray-200">
            <div class="flex items-center justify-center gap-2">
              <div class="w-2 h-2 bg-green-500 rounded-full animate-pulse"></div>
              <p class="text-green-600 font-medium text-sm">Connected</p>
            </div>
          </div>
        </div>


        <div v-else class="text-center py-4">
          <svg class="w-12 h-12 text-gray-300 mx-auto mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z"/>
          </svg>
          <p class="text-sm text-gray-500">No guardian information available</p>
          <p class="text-xs text-gray-400 mt-1">Please contact support</p>
        </div>
      </div>

      <!-- Safety Info Card -->
      <div class="bg-blue-50 border border-blue-200 rounded-2xl p-4">
        <div class="flex items-start">
          <svg class="w-5 h-5 text-blue-600 mr-2 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/>
          </svg>
          <div class="text-sm text-blue-800">
            <p class="font-semibold mb-1">Background Tracking Active</p>
            <p class="text-xs">Your location is tracked even when this app is closed or your phone is locked. This helps your guardian keep you safe.</p>
          </div>
        </div>
      </div>

      <!-- Emergency Button -->
      <div class="bg-white rounded-2xl shadow p-4">
        <button class="w-full bg-red-600 hover:bg-red-700 text-white font-bold py-4 px-6 rounded-xl transition text-lg">
          🚨 Emergency Alert
        </button>
      </div>

    </div>

    <div
        v-if="showLogoutConfirm"
        class="fixed inset-0 bg-black/40 backdrop-blur-sm z-[9998]"
    ></div>

    <div
        v-if="showLogoutConfirm"
        class="fixed inset-0 flex items-center justify-center z-[9999]"
    >
      <div class="bg-white rounded-2xl shadow-xl p-6 w-80 animate-fadeIn">
        <h3 class="text-lg font-semibold text-gray-800 mb-3">
          Confirm Logout
        </h3>

        <p class="text-sm text-gray-600 mb-6">
          Are you sure you want to log out?
        </p>

        <div class="flex justify-end gap-3">
          <button
              @click="cancelLogout"
              class="px-4 py-2 rounded-lg bg-gray-100 hover:bg-gray-200 text-gray-700"
          >
            Cancel
          </button>

          <button
              @click="confirmLogout"
              class="px-4 py-2 rounded-lg bg-red-600 hover:bg-red-700 text-white"
          >
            Logout
          </button>
        </div>
      </div>
    </div>

  </div>
</template>

<style>
@import 'leaflet/dist/leaflet.css';

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: scale(0.9);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

.animate-fadeIn {
  animation: fadeIn 0.2s ease-out;
}
</style>

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