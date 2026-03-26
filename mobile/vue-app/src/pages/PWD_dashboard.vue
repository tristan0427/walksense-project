<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router'
import { TextToSpeech } from '@capacitor-community/text-to-speech';
import { CapacitorHttp } from '@capacitor/core';
import LocationService from "../services/LocationService";
import ObjectDetection from "../types/ObjectDetection";
import axios from "axios";

axios.defaults.baseURL = import.meta.env.VITE_API_BASE_URL;
const token = localStorage.getItem('token');
if (token) {
  axios.defaults.headers.common['Authorization'] = `Bearer ${token}`;
}

const router = useRouter();
const isTracking = ref(false);
const currentLocation = ref(null);
const error = ref('');
const user = ref(null);
const guardian = ref(null);
const menuOpen = ref(false);
const showLogoutConfirm = ref(false);

// ── Camera connection state ──────────────────────────────────────────────────
const dayCamConnected   = ref(false);
const nightCamConnected = ref(false);
const dayCamStatus      = ref('Not Connected');
const nightCamStatus    = ref('Not Connected');

// Camera IPs — stored in localStorage after first setup, editable in settings
const dayCamIp   = ref(localStorage.getItem('dayCamIp')   || '');
const nightCamIp = ref(localStorage.getItem('nightCamIp') || '');

// Active camera — driven by BH1750 lux reading from ESP32
// The ESP32 reports a lux value; Vue decides which cam to use
const activeCamera  = ref('day');   // 'day' | 'night'
const currentLux    = ref(null);    // latest lux from BH1750
const LUX_THRESHOLD = 50;           // below this → night mode
const showIpSetup = ref(false);  // ← never auto-show

const isSendingDistress = ref(false);
const distressSent = ref(false);

// ── Detection state ──────────────────────────────────────────────────────────
const nearestObject     = ref(null);
const detectionInterval = ref(null);
const lastSpokenTime    = ref(0);
const isSpeaking        = ref(false);
const SPEAK_DELAY       = 3000;

// ── IP Setup form ────────────────────────────────────────────────────────────
const ipFormDay   = ref(dayCamIp.value);
const ipFormNight = ref(nightCamIp.value);
const ipSaveError = ref('');

const saveIps = async () => {
  const ipRegex = /^(\d{1,3}\.){3}\d{1,3}$/;

  // Day camera is required
  if (!ipRegex.test(ipFormDay.value.trim())) {
    ipSaveError.value = 'Please enter a valid Day Camera IP (e.g. 192.168.43.101)';
    return;
  }

  // Night camera is optional — only validate if something was entered
  if (ipFormNight.value.trim() && !ipRegex.test(ipFormNight.value.trim())) {
    ipSaveError.value = 'Night Camera IP is invalid (e.g. 192.168.43.102)';
    return;
  }

  ipSaveError.value = '';
  dayCamIp.value   = ipFormDay.value.trim();
  nightCamIp.value = ipFormNight.value.trim();

  localStorage.setItem('dayCamIp', dayCamIp.value);
  if (nightCamIp.value) {
    localStorage.setItem('nightCamIp', nightCamIp.value);
  } else {
    localStorage.removeItem('nightCamIp');  // clear it if left blank
  }

  showIpSetup.value = false;
  await connectBothCameras();
};

// ── Lifecycle ────────────────────────────────────────────────────────────────
onMounted(async () => {
  console.log('PWD Dashboard mounted');

  // Day camera events
  ObjectDetection.addListener('dayCamConnected', () => {
    dayCamConnected.value = true;
    dayCamStatus.value    = 'Connected';
    console.log('✓ Day cam connected');
    maybeStartDetection();
  });
  ObjectDetection.addListener('dayCamError', (data) => {
    dayCamConnected.value = false;
    dayCamStatus.value    = 'Error: ' + data.error;
    console.error('Day cam error:', data.error);
  });

  // Night camera events
  ObjectDetection.addListener('nightCamConnected', () => {
    nightCamConnected.value = true;
    nightCamStatus.value    = 'Connected';
    console.log('✓ Night cam connected');
    maybeStartDetection();
  });
  ObjectDetection.addListener('nightCamError', (data) => {
    nightCamConnected.value = false;
    nightCamStatus.value    = 'Error: ' + data.error;
    console.error('Night cam error:', data.error);
  });

  // Load model
  try {
    await ObjectDetection.loadModel();
    console.log('✓ Model loaded');
  } catch (err) {
    console.error('Model load failed:', err);
  }

  // Load user/guardian
  const userStr = localStorage.getItem('user');
  if (userStr) user.value = JSON.parse(userStr);

  const guardianStr = localStorage.getItem('guardian');
  if (guardianStr) guardian.value = JSON.parse(guardianStr);

  // Connect cameras if IPs are already saved
  if (dayCamIp.value || nightCamIp.value) {
    await connectBothCameras();
  }
  
  if (dayCamIp.value) {
    // Only start polling if day camera (with BH1750 sensor) exists
    startLuxPolling();
  }

  if (nightCamIp.value) {
    startEmergencyPolling();
  }

  // Start GPS tracking
  await startTracking();
});

onUnmounted(async () => {
  stopDetection();
  stopLuxPolling();
  stopEmergencyPolling();
  await ObjectDetection.stopESP32Stream();
  await ObjectDetection.removeAllListeners();
});

// ── Camera Connection ─────────────────────────────────────────────────────────

const connectBothCameras = async () => {
  if (dayCamIp.value) {
    try {
      await ObjectDetection.startDayStream({ ip: dayCamIp.value });
    } catch (err) {
      console.error('Day cam start failed:', err);
      dayCamStatus.value = 'Failed to start';
    }
  }

  if (nightCamIp.value) {   // ← only starts if IP exists
    try {
      await ObjectDetection.startNightStream({ ip: nightCamIp.value });
    } catch (err) {
      console.error('Night cam start failed:', err);
      nightCamStatus.value = 'Failed to start';
    }
  }
};

// ── Light Sensor Handling ───────────────────────────────────────────────────

let luxInterval = null;

const startLuxPolling = () => {
  if (luxInterval) return;
  
  // Need to poll the day board specifically, since it hosts the BH1750
  if (!dayCamIp.value) return; 

  luxInterval = setInterval(async () => {
    try {
      // Use native CapacitorHttp to bypass Mixed Content policies on Android
      const response = await CapacitorHttp.get({
        url: `http://${dayCamIp.value}:82/lux`,
        connectTimeout: 2000,
        readTimeout: 2000
      });
      
      let data = response.data;
      if (typeof data === 'string' && data.length > 0) {
        try { data = JSON.parse(data); } catch { /* ignore */ }
      }
      
      if (data && typeof data.lux === 'number') {
        onLuxReceived(data.lux);
      }
    } catch (err) {
      // Quiet fail — board might be off or busy
    }
  }, 5000);
};

const stopLuxPolling = () => {
  if (luxInterval) {
    clearInterval(luxInterval);
    luxInterval = null;
  }
};

// Call this whenever you receive a lux reading from the ESP32
// (e.g. via a secondary HTTP endpoint or WebSocket from the board)
const onLuxReceived = async (lux) => {
  currentLux.value = lux;
  const newCam = lux < LUX_THRESHOLD ? 'night' : 'day';

  if (newCam !== activeCamera.value) {
    activeCamera.value = newCam;
    console.log(`💡 Lux ${lux} → switching to ${newCam} camera`);
    try {
      await ObjectDetection.switchActiveCamera({ camera: newCam });
      // If detection was off because day stream was originally dead but night is up
      maybeStartDetection();
    } catch (err) {
      console.error('Camera switch failed:', err);
    }
  }
};

// ── Emergency Button Handling ───────────────────────────────────────────────

let emergencyInterval = null;

const startEmergencyPolling = () => {
  if (emergencyInterval) return;
  if (!nightCamIp.value) return;

  emergencyInterval = setInterval(async () => {
    try {
      const response = await CapacitorHttp.get({
        url: `http://${nightCamIp.value}:82/button`,
        connectTimeout: 2000,
        readTimeout: 2000
      });
      
      let data = response.data;
      if (typeof data === 'string' && data.length > 0) {
        try { data = JSON.parse(data); } catch { /* ignore */ }
      }
      
      if (data && data.pressed === true) {
        sendDistressSignal('hardware_button');
      }
    } catch (err) {
      // Quiet fail
    }
  }, 2000);
};

const stopEmergencyPolling = () => {
  if (emergencyInterval) {
    clearInterval(emergencyInterval);
    emergencyInterval = null;
  }
};

const sendDistressSignal = async (source = 'app_button') => {
  if (isSendingDistress.value) return;
  
  isSendingDistress.value = true;
  console.log(`Sending distress signal from ${source}...`);
  
  try {
    let lat = null;
    let lng = null;
    
    // Try to get freshest coordinates
    try {
      const position = await LocationService.getCurrentLocation();
      lat = position.coords.latitude;
      lng = position.coords.longitude;
    } catch (e) {
      if (currentLocation.value) {
        lat = currentLocation.value.latitude;
        lng = currentLocation.value.longitude;
      }
    }

    await axios.post('/api/notifications', {
      type: 'distress',
      is_emergency: true,
      latitude: lat,
      longitude: lng
    });

    // Show flash message
    distressSent.value = true;
    setTimeout(() => {
      distressSent.value = false;
    }, 4000);
    
    // Voice prompt
    try {
      await TextToSpeech.speak({
        text: 'Emergency distress signal sent to your guardian.',
        lang: 'en-US',
      });
    } catch (e) {}

  } catch (err) {
    console.error('Failed to send distress signal:', err);
    error.value = 'Failed to send distress signal: ' + (err.response?.data?.message || err.message);
  } finally {
    isSendingDistress.value = false;
  }
};

// ── Detection ────────────────────────────────────────────────────────────────

// Only start detection loop once at least the active camera has a frame
// const maybeStartDetection = () => {
//   if (detectionInterval.value) return; // already running
//   const activeCamReady = activeCamera.value === 'day'
//       ? dayCamConnected.value
//       : nightCamConnected.value;
//   if (activeCamReady) startDetection();
//   if (dayCamConnected.value) startDetection();
// };

const maybeStartDetection = () => {
  if (detectionInterval.value) return; // already running
  if (dayCamConnected.value || nightCamConnected.value) startDetection();
};

const startDetection = () => {
  console.log('Starting detection loop');
  detectionInterval.value = setInterval(async () => {
    try {
      const result = await ObjectDetection.detectFromStream({ confidence: 0.3 });

      const now      = Date.now();
      const canSpeak = !isSpeaking.value && (now - lastSpokenTime.value >= SPEAK_DELAY);

      if (result.nearest) {
        nearestObject.value = result.nearest;

        if (canSpeak) {
          const msg = `${result.nearest.class} ${result.nearest.distance} on the ${result.nearest.direction}`;
          isSpeaking.value = true;
          try {
            await TextToSpeech.speak({
              text: msg,
              lang: 'en-US',
              rate: 0.9,
              pitch: 1.0,
              volume: 1.0
            });
            lastSpokenTime.value = Date.now();
          } catch (ttsError) {
            console.error('TTS error:', ttsError);
          } finally {
            isSpeaking.value = false;
          }
        }
      } else {
        nearestObject.value = null;
      }

    } catch (err) {
      // Suppress "no frame available" during camera warm-up
      if (!err.message?.includes('No valid')) {
        console.error('Detection error:', err);
      }
      isSpeaking.value = false;
    }
  }, 1000);
};

const stopDetection = () => {
  if (detectionInterval.value) clearInterval(detectionInterval.value);
  detectionInterval.value = null;
};

// ── GPS ───────────────────────────────────────────────────────────────────────
const startTracking = async () => {
  try {
    error.value = '';
    await LocationService.startTracking();
    isTracking.value = true;
    const position = await LocationService.getCurrentLocation();
    currentLocation.value = position.coords;
  } catch (err) {
    console.error('Tracking error:', err);
    setTimeout(async () => {
      try {
        await LocationService.startTracking();
        isTracking.value = true;
        const position = await LocationService.getCurrentLocation();
        currentLocation.value = position.coords;
        error.value = '';
      } catch (retryErr) {
        error.value = 'GPS unavailable. Please enable Location/GPS in your phone settings.';
        isTracking.value = false;
      }
    }, 5000);
  }
};

const stopTracking = async () => {
  try {
    await LocationService.stopTracking();
    isTracking.value = false;
  } catch (err) {
    error.value = err.message || 'Failed to stop tracking';
  }
};

// ── Auth ──────────────────────────────────────────────────────────────────────
const logout         = () => { showLogoutConfirm.value = true; };
const cancelLogout   = () => { showLogoutConfirm.value = false; };
const confirmLogout  = () => {
  localStorage.removeItem('token');
  localStorage.removeItem('user');
  localStorage.removeItem('guardian');
  showLogoutConfirm.value = false;
  router.push('/');
};
const goAccount = () => { router.push('/account'); };

const goToWearableSetup = () => {
  router.push('/wearable-setup')
}
</script>

<template>
  <div class="min-h-screen bg-gray-100 flex flex-col">

    <!-- Header -->
    <header class="bg-[#f7d686] shadow-md px-4 py-3 flex items-center justify-between">
      <h1 class="text-lg font-bold text-gray-800">WALKSENSE</h1>
      <button @click="menuOpen = !menuOpen" class="text-gray-700 text-2xl">☰</button>
    </header>

    <!-- Slide Menu -->
    <div v-if="menuOpen" class="absolute right-4 mt-14 w-40 bg-white rounded-xl shadow-lg border z-50">
      <button @click="goToWearableSetup" class="block w-full text-left px-4 py-3 hover:bg-gray-100 text-sm">
         Camera Setup
      </button>
      <button @click="goAccount" class="block w-full text-left px-4 py-3 hover:bg-gray-100">Account</button>
      <button @click="logout" class="block w-full text-left px-4 py-3 text-red-600 hover:bg-gray-100">Logout</button>
    </div>

    <!-- ── IP Setup Modal ── -->
    <div v-if="showIpSetup" class="fixed inset-0 bg-black/50 z-50 flex items-center justify-center px-4">
      <div class="bg-white rounded-2xl shadow-xl p-6 w-full max-w-sm">
        <h3 class="text-lg font-bold text-gray-800 mb-1">Camera Setup</h3>
        <p class="text-xs text-gray-500 mb-4">
          Enter the IP addresses shown in the Serial Monitor after the boards connect to your hotspot.
        </p>

        <div class="space-y-4">
          <!-- Day Camera -->
          <div>
            <label class="block text-sm font-semibold text-gray-700 mb-1">
              ☀️ Day Camera IP
            </label>
            <input
                v-model="ipFormDay"
                type="text"
                placeholder="e.g. 192.168.43.101"
                class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-yellow-400"
            />
          </div>

          <!-- Night Camera -->
          <div>
            <label class="block text-sm font-semibold text-gray-700 mb-1">
              🌙 Night Camera IP
            </label>
            <input
                v-model="ipFormNight"
                type="text"
                placeholder="e.g. 192.168.43.102"
                class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-yellow-400"
            />
          </div>

          <!-- Error -->
          <p v-if="ipSaveError" class="text-xs text-red-600">{{ ipSaveError }}</p>

          <div class="flex gap-3 pt-1">
            <button
                v-if="dayCamIp"
                @click="showIpSetup = false"
                class="flex-1 py-2 rounded-lg bg-gray-100 hover:bg-gray-200 text-gray-700 text-sm"
            >
              Cancel
            </button>
            <button
                @click="saveIps"
                class="flex-1 py-2 rounded-lg bg-yellow-400 hover:bg-yellow-500 text-gray-900 font-semibold text-sm"
            >
              Connect Cameras
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Main Content -->
    <div class="p-4 space-y-4">

      <!-- Online Status -->
      <div class="bg-yellow-100 rounded-2xl shadow p-4 flex items-center">
        <div class="w-10 h-10 bg-gray-800 rounded-full flex items-center justify-center mr-3">
          <svg class="w-6 h-6 text-white" fill="currentColor" viewBox="0 0 20 20">
            <path fill-rule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clip-rule="evenodd"/>
          </svg>
        </div>
        <p class="font-medium text-gray-800">You are Online!</p>
      </div>

      <!-- Greeting -->
      <div class="bg-yellow-100 rounded-2xl shadow p-4">
        <h2 class="text-xl font-semibold text-gray-800">
          Hi {{ user?.name || 'User' }}!
        </h2>
      </div>

      <!-- GPS Error -->
      <div v-if="error"
           class="bg-red-100 border-4 border-red-500 text-red-700 rounded-2xl p-4 animate-pulse">
        <p class="text-base font-bold">⚠️ TRACKING ERROR</p>
        <p class="text-sm font-medium mt-2">{{ error }}</p>
        <p class="text-xs text-red-600 mt-2">Please check your location permissions and GPS settings.</p>
      </div>

      <!-- GPS Status -->
      <div class="bg-yellow-100 rounded-2xl shadow p-4">
        <div class="flex items-center justify-between mb-3">
          <h2 class="font-semibold text-gray-800">GPS Tracking</h2>
          <div :class="['w-3 h-3 rounded-full', isTracking ? 'bg-green-500 animate-pulse' : 'bg-red-500']"></div>
        </div>
        <div class="flex items-center">
          <div class="w-10 h-10 bg-gray-800 rounded-full flex items-center justify-center mr-3">
            <svg v-if="isTracking" class="w-6 h-6 text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"/>
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 11a3 3 0 11-6 0 3 3 0 016 0z"/>
            </svg>
            <svg v-else class="w-6 h-6 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636"/>
            </svg>
          </div>
          <div>
            <p class="font-medium text-gray-800">{{ isTracking ? 'Tracking Active' : 'Tracking Inactive' }}</p>
            <p class="text-xs text-gray-600">
              {{ isTracking ? 'Your guardian can see your location' : 'Location sharing paused' }}
            </p>
          </div>
        </div>
        <div v-if="currentLocation" class="mt-3 pt-3 border-t border-yellow-200">
          <div class="grid grid-cols-2 gap-2 text-xs">
            <div>
              <p class="text-gray-600">Latitude</p>
              <p class="font-mono font-semibold text-gray-800">{{ currentLocation.latitude?.toFixed(6) }}</p>
            </div>
            <div>
              <p class="text-gray-600">Longitude</p>
              <p class="font-mono font-semibold text-gray-800">{{ currentLocation.longitude?.toFixed(6) }}</p>
            </div>
            <div>
              <p class="text-gray-600">Accuracy</p>
              <p class="font-semibold text-gray-800">±{{ currentLocation.accuracy?.toFixed(0) }}m</p>
            </div>
            <div v-if="currentLocation.speed">
              <p class="text-gray-600">Speed</p>
              <p class="font-semibold text-gray-800">{{ (currentLocation.speed * 3.6).toFixed(1) }} km/h</p>
            </div>
          </div>
        </div>
      </div>

      <!-- ── Dual Camera Status ── -->
      <div class="bg-white rounded-2xl shadow p-4 space-y-3">
        <div class="flex items-center justify-between">
          <h2 class="font-semibold text-gray-800">Camera Status</h2>
          <!-- Active camera badge -->
          <span :class="[
            'text-xs font-bold px-2 py-1 rounded-full',
            activeCamera === 'day' ? 'bg-yellow-200 text-yellow-800' : 'bg-indigo-200 text-indigo-800'
          ]">
            {{ activeCamera === 'day' ? '☀️ Day Mode' : '🌙 Night Mode' }}
          </span>
        </div>

        <!-- Light level if available -->
        <div v-if="currentLux !== null" class="text-xs text-gray-500">
          💡 Light level: {{ currentLux }} lux
          <span class="ml-1 text-gray-400">(threshold: {{ LUX_THRESHOLD }} lux)</span>
        </div>

        <!-- Day camera row -->
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <div :class="[
              'w-3 h-3 rounded-full flex-shrink-0',
              dayCamConnected ? 'bg-green-500 animate-pulse' : 'bg-red-400'
            ]"></div>
            <div>
              <p class="text-sm font-medium text-gray-800">☀️ Day Camera</p>
              <p class="text-xs text-gray-500">{{ dayCamIp || 'No IP set' }}</p>
            </div>
          </div>
          <span :class="[
            'text-xs px-2 py-0.5 rounded-full font-medium',
            dayCamConnected ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-600'
          ]">
            {{ dayCamConnected ? 'Online' : dayCamStatus }}
          </span>
        </div>

        <!-- Night camera row -->
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <div :class="[
              'w-3 h-3 rounded-full flex-shrink-0',
              nightCamConnected ? 'bg-green-500 animate-pulse' : 'bg-red-400'
            ]"></div>
            <div>
              <p class="text-sm font-medium text-gray-800">🌙 Night Camera</p>
              <p class="text-xs text-gray-500">{{ nightCamIp || 'No IP set' }}</p>
            </div>
          </div>
          <span :class="[
            'text-xs px-2 py-0.5 rounded-full font-medium',
            nightCamConnected ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-600'
          ]">
            {{ nightCamConnected ? 'Online' : nightCamStatus }}
          </span>
        </div>

        <!-- Reconnect / Setup button -->
        <button
            @click="showIpSetup = true"
            class="w-full mt-1 py-2 text-xs rounded-lg border border-gray-300 text-gray-600 hover:bg-gray-50"
        >
          ⚙️ Change Camera IPs
        </button>
      </div>

      <!-- Detection -->
      <div class="bg-white rounded-xl p-4">
        <div class="flex items-center justify-between mb-3">
          <h2 class="font-bold">Nearest Obstacle</h2>
          <span class="text-xs text-gray-400">via {{ nearestObject?.camera || activeCamera }} cam</span>
        </div>

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
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                  d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"/>
          </svg>
          Guardian Information
        </h2>
        <div v-if="guardian">
          <div class="space-y-2">
            <div class="flex items-start">
              <svg class="w-4 h-4 text-gray-500 mr-2 mt-0.5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"/>
              </svg>
              <div>
                <p class="text-xs text-gray-500">Name</p>
                <p class="text-sm font-semibold text-gray-800">{{ guardian.name }}</p>
              </div>
            </div>
            <div class="flex items-start">
              <svg class="w-4 h-4 text-gray-500 mr-2 mt-0.5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"/>
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
          <p class="text-sm text-gray-500">No guardian information available</p>
          <p class="text-xs text-gray-400 mt-1">Please contact support</p>
        </div>
      </div>

      <!-- Background Tracking Note -->
      <div class="bg-blue-50 border border-blue-200 rounded-2xl p-4">
        <div class="flex items-start">
          <svg class="w-5 h-5 text-blue-600 mr-2 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                  d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/>
          </svg>
          <div class="text-sm text-blue-800">
            <p class="font-semibold mb-1">Background Tracking Active</p>
            <p class="text-xs">Your location is tracked even when this app is closed or your phone is locked.</p>
          </div>
        </div>
      </div>

      <!-- TEMPORARY DISTRESS BUTTON -->
      <div class="mt-4 mb-2">
        <button 
          @click="sendDistressSignal('app_button')"
          :disabled="isSendingDistress"
          class="w-full py-4 rounded-2xl shadow-lg border-2 border-red-600 bg-red-500 hover:bg-red-600 text-white font-bold text-lg flex items-center justify-center gap-3 transition-colors active:scale-95"
        >
          <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24" v-if="!isSendingDistress">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
          </svg>
          <svg class="w-8 h-8 animate-spin" fill="none" viewBox="0 0 24 24" v-else>
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A8.001 8.001 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
          </svg>
          {{ isSendingDistress ? 'SENDING SOS...' : 'SEND EMERGENCY DISTRESS' }}
        </button>
      </div>

      <!-- Success Flash Message -->
      <div v-if="distressSent" class="fixed top-20 left-4 right-4 bg-red-100 border-l-4 border-red-600 text-red-800 p-4 rounded shadow-xl z-50 animate-fadeIn">
        <div class="flex">
          <svg class="w-6 h-6 text-red-600 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
          </svg>
          <div>
            <p class="font-bold">Distress Signal Sent!</p>
            <p class="text-xs mt-1">Your guardian has been notified of your location.</p>
          </div>
        </div>
      </div>

    </div><!-- /main content -->

    <!-- Logout overlay -->
    <div v-if="showLogoutConfirm" class="fixed inset-0 bg-black/40 backdrop-blur-sm z-[9998]"></div>
    <div v-if="showLogoutConfirm" class="fixed inset-0 flex items-center justify-center z-[9999]">
      <div class="bg-white rounded-2xl shadow-xl p-6 w-80 animate-fadeIn">
        <h3 class="text-lg font-semibold text-gray-800 mb-3">Confirm Logout</h3>
        <p class="text-sm text-gray-600 mb-6">Are you sure you want to log out?</p>
        <div class="flex justify-end gap-3">
          <button @click="cancelLogout"
                  class="px-4 py-2 rounded-lg bg-gray-100 hover:bg-gray-200 text-gray-700">Cancel</button>
          <button @click="confirmLogout"
                  class="px-4 py-2 rounded-lg bg-red-600 hover:bg-red-700 text-white">Logout</button>
        </div>
      </div>
    </div>

  </div>
</template>

<style>
@import 'leaflet/dist/leaflet.css';

@keyframes fadeIn {
  from { opacity: 0; transform: scale(0.9); }
  to   { opacity: 1; transform: scale(1);   }
}
.animate-fadeIn { animation: fadeIn 0.2s ease-out; }
</style>