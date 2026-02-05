<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import axios from "axios";

const router = useRouter()
const menuOpen = ref(false)
const mapContainer = ref(null)
const map = ref(null)
const markers = ref(new Map())
const pwdLocations = ref([])
const loading = ref(true)
const error = ref('')
const updateInterval = ref(null)

axios.defaults.baseURL = 'http://172.23.172.98:8000'
const token = localStorage.getItem('token')
if (token) {
  axios.defaults.headers.common['Authorization'] = `Bearer ${token}`
}

onMounted(async () => {
  await initializeMap()
  await loadPwdLocations()

  updateInterval.value = setInterval(() => {
    loadPwdLocations()
  },10000)
})

onUnmounted(() => {
  if(updateInterval.value){
    clearInterval(updateInterval.value)
  }
})

const initializeMap = async () => {
  try {
    if (!mapContainer.value) return


    map.value = L.map(mapContainer.value).setView([7.4474, 125.8078], 13)


    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap',
      maxZoom: 19,
    }).addTo(map.value)

    console.log('Map initialized')
  } catch (err) {
    error.value = 'Failed to load map'
    console.error('Map error:', err)
  }
}

const loadPwdLocations = async () => {
  try {
    const response = await axios.get('/api/location/all-pwds')
    pwdLocations.value = response.data.pwd_locations

    updateMapMarkers()

    loading.value = false
  }catch (err) {
    console.error('Failed to load locations:', err)
    error.value = err.response?.data?.message || 'Failed to load locations'
    loading.value = false
  }
}

const updateMapMarkers = () => {
  if (!map.value) return

  pwdLocations.value.forEach((pwd) => {
    if (!pwd.location) return

    const position = [
      parseFloat(pwd.location.latitude),
      parseFloat(pwd.location.longitude),
    ]

    // Check if marker exists
    if (markers.value.has(pwd.pwd_id)) {
      // Update existing marker
      const marker = markers.value.get(pwd.pwd_id)
      marker?.setLatLng(position)
    } else {
      // Create custom marker
      const customIcon = L.divIcon({
        className: 'custom-marker',
        html: `
          <div style="
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            width: 32px;
            height: 32px;
            border-radius: 50% 50% 50% 0;
            transform: rotate(-45deg);
            border: 3px solid white;
            box-shadow: 0 3px 8px rgba(0,0,0,0.3);
            display: flex;
            align-items: center;
            justify-content: center;
          ">
            <div style="transform: rotate(45deg); font-size: 16px;">📍</div>
          </div>
        `,
        iconSize: [32, 32],
        iconAnchor: [16, 32],
        popupAnchor: [0, -32],
      })

      // Create marker
      const marker = L.marker(position, { icon: customIcon }).addTo(map.value)

      // Add popup
      const isOnline = isPwdOnline(pwd.location.last_updated)

      marker.bindPopup(`
        <div style="min-width: 200px; font-family: sans-serif;">
          <h3 style="margin: 0 0 8px 0; font-weight: bold;">${pwd.pwd_name}</h3>

          <span style="
            display: inline-block;
            padding: 3px 8px;
            border-radius: 10px;
            font-size: 11px;
            font-weight: bold;
            margin-bottom: 8px;
            ${isOnline ? 'background: #10b981; color: white;' : 'background: #6b7280; color: white;'}
          ">
            ${isOnline ? '🟢 Online' : '⚪ Offline'}
          </span>

          <div style="font-size: 12px; color: #374151;">
            <p style="margin: 4px 0;">
              📍 ${parseFloat(pwd.location.latitude).toFixed(6)}, ${parseFloat(pwd.location.longitude).toFixed(6)}
            </p>
            ${pwd.location.accuracy
          ? `<p style="margin: 4px 0;">
      🎯 ±${Number(pwd.location.accuracy).toFixed(0)}m
    </p>`
          : ''
      }
            ${pwd.location.battery_level ? `<p style="margin: 4px 0;">🔋 ${pwd.location.battery_level}%</p>` : ''}
            <p style="margin: 4px 0; font-size: 11px; color: #6b7280;">
              🕒 ${formatLastUpdated(pwd.location.last_updated)}
            </p>
          </div>
        </div>
      `)

      markers.value.set(pwd.pwd_id, marker)
    }
  })


  if (pwdLocations.value.length > 0 && pwdLocations.value[0].location) {
    const loc = pwdLocations.value[0].location
    map.value?.setView([parseFloat(loc.latitude), parseFloat(loc.longitude)], 15)
  }
}

const isPwdOnline = (lastUpdated) => {
  const lastUpdate = new Date(lastUpdated)
  const now = new Date()
  const diffMinutes = (now.getTime() - lastUpdate.getTime()) / 60000
  return diffMinutes < 2
}

const formatLastUpdated = (dateString) => {
  const date = new Date(dateString)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)

  if (diffMins < 1) return 'Just now'
  if (diffMins < 60) return `${diffMins} min ago`

  const diffHours = Math.floor(diffMins / 60)
  if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`

  return date.toLocaleDateString()
}




const logout = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  router.push('/login?role=guardian')
}

const goAccount = () => {
  router.push('/account')
}

const refreshLocations = () => {
  loading.value = true
  loadPwdLocations()
}
</script>

<template>
  <div class="min-h-screen bg-gray-100 flex flex-col">

    <header class="bg-white shadow-md px-4 py-3 flex items-center justify-between">
      <h1 class="text-lg font-bold text-gray-800">Guardian Dashboard</h1>

      <div class="flex items-center gap-2">
        <!-- Refresh Button -->
        <button
            @click="refreshLocations"
            :disabled="loading"
            class="p-2 rounded-lg hover:bg-gray-100 transition"
            title="Refresh"
        >
          <svg
              :class="['w-5 h-5 text-gray-700', loading ? 'animate-spin' : '']"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
          >
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/>
          </svg>
        </button>

        <!-- Menu Button -->
        <button @click="menuOpen = !menuOpen" class="text-gray-700 text-2xl">
          ☰
        </button>
      </div>
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

    <!-- Error Message -->
    <div v-if="error" class="bg-red-100 border-l-4 border-red-500 text-red-700 p-4 mx-4 mt-4 rounded">
      {{ error }}
    </div>

    <div class="flex-1 flex flex-col p-4 space-y-4">

      <!-- PWD Status Cards -->
      <div class="space-y-3">
        <div
            v-for="pwd in pwdLocations"
            :key="pwd.pwd_id"
            class="bg-white rounded-2xl shadow p-4"
        >
          <div class="flex items-center justify-between mb-2">
            <h2 class="font-semibold text-gray-800">{{ pwd.pwd_name }}</h2>

            <!-- Online/Offline Badge -->
            <div class="flex items-center gap-2">
              <!-- Pulse dot -->
              <span
                  v-if="pwd.location && isPwdOnline(pwd.location.last_updated)"
                  class="relative flex h-3 w-3"
              >
                <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                <span class="relative inline-flex rounded-full h-3 w-3 bg-green-500"></span>
              </span>
              <span
                  v-else-if="pwd.location"
                  class="h-3 w-3 rounded-full bg-gray-400"
              ></span>

              <span
                  :class="[
                  'px-2 py-1 rounded-full text-xs font-semibold',
                  pwd.location && isPwdOnline(pwd.location.last_updated)
                    ? 'bg-green-100 text-green-800'
                    : 'bg-gray-100 text-gray-800'
                ]"
              >
                {{ pwd.location && isPwdOnline(pwd.location.last_updated) ? 'Online' : 'Offline' }}
              </span>
            </div>
          </div>

          <div v-if="pwd.location">
            <p class="text-sm text-gray-600">
              Last Updated: {{ formatLastUpdated(pwd.location.last_updated) }}
            </p>

            <div v-if="pwd.location.battery_level" class="mt-2 flex items-center text-sm">
              <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"/>
              </svg>
              <span
                  :class="[
                  'font-medium',
                  pwd.location.battery_level > 50 ? 'text-green-600' :
                  pwd.location.battery_level > 20 ? 'text-yellow-600' : 'text-red-600'
                ]"
              >
                Battery: {{ pwd.location.battery_level }}%
              </span>
            </div>

            <!-- Offline Warning -->
            <div
                v-if="!isPwdOnline(pwd.location.last_updated)"
                class="mt-2 p-2 bg-yellow-50 border border-yellow-200 rounded text-xs text-yellow-800"
            >
              ⚠️ Device appears offline. Last location from {{ formatLastUpdated(pwd.location.last_updated) }}.
            </div>
          </div>
          <div v-else>
            <p class="text-sm text-gray-500 italic">No location data yet</p>
          </div>
        </div>

        <!-- No PWDs Message -->
        <div v-if="pwdLocations.length === 0 && !loading" class="bg-white rounded-2xl shadow p-6 text-center">
          <svg class="w-16 h-16 mx-auto mb-3 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"/>
          </svg>
          <p class="text-gray-500">No PWD locations available</p>
        </div>
      </div>

      <!-- Live Location Map -->
      <div class="bg-white rounded-2xl shadow p-4 flex-1">
        <h2 class="font-semibold text-gray-800 mb-3">Live Location Map</h2>
        <div
            ref="mapContainer"
            class="h-64 lg:h-96 bg-gray-200 rounded-xl overflow-hidden"
        >
          <!-- Leaflet map will render here -->
        </div>
        <p class="text-xs text-gray-500 mt-2">
          📍 Map updates every 10 seconds • Click markers for details
        </p>
      </div>

      <!-- Alerts -->
      <div class="bg-white rounded-2xl shadow p-4">
        <h2 class="font-semibold text-gray-800 mb-2">Alerts</h2>
        <p class="text-sm text-gray-600">No alerts right now</p>
      </div>

    </div>

  </div>
</template>

