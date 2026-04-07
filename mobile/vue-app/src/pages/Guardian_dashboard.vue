<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import axios from "axios";
import markerPinUrl from '/gps-mark-pin.png'

const router = useRouter()
const menuOpen = ref(false)
const initialViewSet = ref(false)
const mapContainer = ref(null)
const map = ref(null)
const markers = ref(new Map())
const pwdLocations = ref([])
const notifications = ref([])
const loading = ref(true)
const error = ref('')
const updateInterval = ref(null)
const showLogoutConfirm = ref(false)

axios.defaults.baseURL = import.meta.env.VITE_API_BASE_URL
const token = localStorage.getItem('token')
if (token) {
  axios.defaults.headers.common['Authorization'] = `Bearer ${token}`
}

onMounted(async () => {
  await initializeMap()
  await loadPwdLocations()
  await loadNotifications()

  updateInterval.value = setInterval(() => {
    loadPwdLocations()
    loadNotifications()
  },10000)
})

onUnmounted(() => {
  if(updateInterval.value){
    clearInterval(updateInterval.value)
  }
})

const navigateToPwd = (lat, lng) => {
  navigator.geolocation.getCurrentPosition(
      (position) => {
        const origin = `${position.coords.latitude},${position.coords.longitude}`
        const destination = `${lat},${lng}`
        window.open(
            `https://www.google.com/maps/dir/?api=1&origin=${origin}&destination=${destination}&travelmode=driving`,
            '_blank'
        )
      },
      () => {
        window.open(
            `https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}&travelmode=driving`,
            '_blank'
        )
      }
  )
}

window._navigateToPwd = navigateToPwd

const initializeMap = async () => {
  try {
    if (!mapContainer.value) return


    map.value = L.map(mapContainer.value, {
      attributionControl: false,
    }).setView([7.4474, 125.8078], 15)

    L.tileLayer('https://tiles.stadiamaps.com/tiles/outdoors/{z}/{x}/{y}{r}.png?api_key=' + import.meta.env.VITE_STADIA_API_KEY, {
      attribution: '&copy; <a href="https://stadiamaps.com/">Stadia Maps</a>' +
          ' &copy; <a href="https://openmaptiles.org/">OpenMapTiles</a>' +
          ' &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      maxZoom: 20,
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

const loadNotifications = async () => {
  try {
    const response = await axios.get('/api/notifications')
    notifications.value = response.data.notifications
  } catch(err) {
    console.error('Failed to load notifications:', err)
  }
}

const deleteNotification = async (id) => {
  try {
    await axios.delete(`/api/notifications/${id}`)
    notifications.value = notifications.value.filter(n => n.id !== id)
  } catch (err) {
    console.error('Failed to delete notification:', err)
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
      const customIcon = L.icon({
        iconUrl: markerPinUrl,
        iconSize: [40, 40],
        iconAnchor: [20, 40],
        popupAnchor: [0, -40],
      })

      const marker = L.marker(position, { icon: customIcon }).addTo(map.value)


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
        ${parseFloat(pwd.location.latitude).toFixed(6)}, ${parseFloat(pwd.location.longitude).toFixed(6)}
      </p>
      ${pwd.location.accuracy
          ? `<p style="margin: 4px 0;">±${Number(pwd.location.accuracy).toFixed(0)}m</p>`
          : ''
      }
      ${pwd.location.battery_level ? `<p style="margin: 4px 0;">🔋 ${pwd.location.battery_level}%</p>` : ''}
      <p style="margin: 4px 0; font-size: 11px; color: #6b7280;">
        ${formatLastUpdated(pwd.location.last_updated)}
      </p>
    </div>

        <button onclick="window._navigateToPwd(${pwd.location.latitude}, ${pwd.location.longitude})"
       style="
         display: block;
         width: 100%;
         margin-top: 10px;
         padding: 8px 12px;
         background: #f7d686;
         color: #5a3e00;
         text-align: center;
         text-decoration: none;
         border-radius: 8px;
         font-weight: bold;
         font-size: 13px;
         border: none;
         cursor: pointer;
       ">
        Navigate to PWD
    </button>
  </div>
`)

      markers.value.set(pwd.pwd_id, marker)
    }
  })


  if (!initialViewSet.value && pwdLocations.value.length > 0 && pwdLocations.value[0].location) {
    const loc = pwdLocations.value[0].location
    map.value?.setView([parseFloat(loc.latitude), parseFloat(loc.longitude)], 17)
    initialViewSet.value = true
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
  showLogoutConfirm.value = true
}
const cancelLogout = () => {
  showLogoutConfirm.value = false
}

const confirmLogout = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  showLogoutConfirm.value = false
  router.push('/')
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
  <div class="min-h-screen bg-[#fafaf7] flex flex-col">

    <!-- Header -->
    <header class="bg-[#f7d686] border-b border-yellow-300 px-4 py-3 flex items-center justify-between drop-shadow-sm">
      <h1 class="text-sm font-black tracking-widest uppercase text-gray-800">WALKSENSE</h1>

      <div class="flex items-center gap-2">
        <!-- Refresh -->
        <button @click="refreshLocations" :disabled="loading"
                class="p-2 rounded-lg hover:bg-yellow-400/40 transition" title="Refresh">
          <svg :class="['w-5 h-5 text-gray-700', loading ? 'animate-spin' : '']"
               fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/>
          </svg>
        </button>

        <!-- Menu -->
        <button @click="menuOpen = !menuOpen" class="p-1 rounded-lg hover:bg-yellow-400/40 transition">
          <svg class="w-6 h-6 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16"/>
          </svg>
        </button>
      </div>
    </header>

    <!-- Slide Menu -->
    <div v-if="menuOpen" class="absolute right-4 mt-2 w-44 bg-white rounded-xl shadow-lg ring-1 ring-black/5 z-50 overflow-hidden transition-all duration-150">
      <button @click="goAccount" class="block w-full text-left px-4 py-3 hover:bg-gray-50 text-sm font-medium text-gray-700">
        Account
      </button>
      <button @click="logout" class="block w-full text-left px-4 py-3 text-red-600 font-semibold hover:bg-red-50 text-sm">
        Logout
      </button>
    </div>

    <!-- Error -->
    <div v-if="error" class="bg-red-100 border-l-4 border-red-500 text-red-700 p-4 mx-4 mt-4 rounded-r-xl">
      {{ error }}
    </div>

    <div class="flex-1 flex flex-col p-4 space-y-4">

      <!-- Greeting -->
      <div class="bg-yellow-100 rounded-2xl shadow ring-1 ring-yellow-200/60 p-4">
        <h2 class="text-lg font-bold text-gray-800">Hi Guardian!</h2>
      </div>

      <!-- PWD Status Cards -->
      <div class="space-y-3">
        <div v-for="pwd in pwdLocations" :key="pwd.pwd_id"
             class="bg-white rounded-2xl shadow ring-1 ring-gray-100 p-4 transition-shadow hover:shadow-md">
          <div class="flex items-center justify-between mb-2">
            <h2 class="text-sm font-bold tracking-tight text-gray-800">{{ pwd.pwd_name }}</h2>

            <div class="flex items-center gap-2">
              <span v-if="pwd.location && isPwdOnline(pwd.location.last_updated)" class="relative flex h-3 w-3">
                <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                <span class="relative inline-flex rounded-full h-3 w-3 bg-green-500"></span>
              </span>
              <span v-else-if="pwd.location" class="h-3 w-3 rounded-full bg-gray-400"></span>

              <span :class="[
                'px-2 py-1 rounded-full text-xs font-semibold',
                pwd.location && isPwdOnline(pwd.location.last_updated)
                  ? 'bg-green-100 text-green-800'
                  : 'bg-gray-100 text-gray-800'
              ]">
                {{ pwd.location && isPwdOnline(pwd.location.last_updated) ? 'Online' : 'Offline' }}
              </span>
            </div>
          </div>

          <div v-if="pwd.location">
            <p class="text-xs text-gray-500">
              Last Updated: {{ formatLastUpdated(pwd.location.last_updated) }}
            </p>

            <!-- Battery bar -->
            <div v-if="pwd.location.battery_level" class="mt-3">
              <div class="flex items-center justify-between mb-1">
                <div class="flex items-center gap-1.5 text-xs">
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"/>
                  </svg>
                  <span class="font-medium text-gray-700">Battery</span>
                </div>
                <span :class="[
                  'text-xs font-bold',
                  pwd.location.battery_level > 50 ? 'text-green-600' :
                  pwd.location.battery_level > 20 ? 'text-yellow-600' : 'text-red-600'
                ]">
                  {{ pwd.location.battery_level }}%
                </span>
              </div>
              <div class="w-full h-1.5 bg-gray-100 rounded-full overflow-hidden">
                <div :class="[
                  'h-full rounded-full transition-all duration-500',
                  pwd.location.battery_level > 50 ? 'bg-green-500' :
                  pwd.location.battery_level > 20 ? 'bg-yellow-500' : 'bg-red-500'
                ]" :style="{ width: pwd.location.battery_level + '%' }"></div>
              </div>
            </div>

            <!-- GPS accuracy pill -->
            <div v-if="pwd.location.accuracy" class="mt-2">
              <span class="text-[10px] font-medium tracking-wide uppercase text-gray-400 bg-gray-50 px-2 py-0.5 rounded-full">
                GPS &plusmn;{{ Number(pwd.location.accuracy).toFixed(0) }}m
              </span>
            </div>

            <!-- Offline warning -->
            <div v-if="!isPwdOnline(pwd.location.last_updated)"
                 class="mt-2 p-2 bg-yellow-50 border border-yellow-200 rounded-lg flex items-start gap-2">
              <svg class="w-4 h-4 text-yellow-600 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
              </svg>
              <p class="text-xs font-medium text-yellow-800">Device appears offline. Last location from {{ formatLastUpdated(pwd.location.last_updated) }}.</p>
            </div>
          </div>
          <div v-else>
            <p class="text-sm text-gray-500 italic">No location data yet</p>
          </div>
        </div>

        <!-- No PWDs -->
        <div v-if="pwdLocations.length === 0 && !loading" class="bg-white rounded-2xl shadow ring-1 ring-gray-100 p-6 text-center">
          <svg class="w-16 h-16 mx-auto mb-3 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"/>
          </svg>
          <p class="text-gray-500">No PWD locations available</p>
        </div>
      </div>

      <!-- Live Location Map -->
      <div class="bg-white rounded-2xl shadow ring-1 ring-gray-100 p-4 flex-1 transition-shadow hover:shadow-md">
        <div class="flex items-center gap-2 mb-3">
          <span class="w-2 h-2 bg-green-500 rounded-full animate-pulse"></span>
          <h2 class="text-sm font-bold tracking-tight text-gray-800">Live Location Map</h2>
        </div>
        <div ref="mapContainer" class="h-80 lg:h-96 bg-gray-200 rounded-xl overflow-hidden"></div>
        <p class="text-[10px] font-medium tracking-wide uppercase text-gray-400 mt-2">
          Updates every 10 seconds • Click markers for details
        </p>
      </div>

      <!-- Emergency Alerts -->
      <div class="bg-white rounded-2xl shadow ring-1 ring-gray-100 p-4 transition-shadow hover:shadow-md">
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-sm font-bold tracking-tight text-gray-800 flex items-center gap-2">
            <svg class="w-5 h-5 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"/>
            </svg>
            Emergency Alerts
          </h2>
          <span v-if="notifications.length" class="bg-red-100 text-red-700 font-bold text-xs px-2 py-1 rounded-full">{{ notifications.length }}</span>
        </div>

        <!-- Empty state -->
        <div v-if="notifications.length === 0" class="text-center py-6">
          <svg class="w-12 h-12 mx-auto text-green-300 mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/>
          </svg>
          <p class="text-sm text-green-600 font-semibold">No active alerts</p>
        </div>

        <!-- Alert cards -->
        <div v-else class="space-y-3 max-h-80 overflow-y-auto pr-1">
          <div v-for="notif in notifications" :key="notif.id"
               class="border border-red-200 bg-red-50 rounded-xl p-3 shadow-sm transition-all hover:shadow-md animate-fadeIn">
            <div class="flex justify-between items-start">
              <div>
                <h3 class="text-sm font-bold text-red-700 flex items-center gap-2">
                  <svg class="w-4 h-4 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
                  </svg>
                  Distress Signal
                </h3>
                <p class="text-xs text-gray-600 font-medium tracking-wide mt-1">{{ notif.pwd?.user?.name || 'Your PWD' }}</p>
                <p class="text-[10px] text-gray-400 mt-1 uppercase tracking-wide">{{ new Date(notif.triggered_at).toLocaleString() }}</p>
              </div>
            </div>

            <div class="mt-3 flex gap-2">
              <button @click="map && map.setView([notif.latitude, notif.longitude], 18)"
                      v-if="notif.latitude && notif.longitude"
                      class="flex-1 py-1.5 px-3 text-xs font-semibold rounded-lg bg-red-600 text-white hover:bg-red-700 transition-colors">
                Locate on Map
              </button>
              <button @click="deleteNotification(notif.id)"
                      class="py-1.5 px-3 text-xs font-medium rounded-lg text-gray-500 hover:text-red-700 hover:bg-red-100 transition-colors">
                Dismiss
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Logout overlay -->
    <div v-if="showLogoutConfirm" class="fixed inset-0 bg-black/40 backdrop-blur-sm z-[9998]"></div>
    <div v-if="showLogoutConfirm" class="fixed inset-0 flex items-center justify-center z-[9999]">
      <div class="bg-white rounded-2xl shadow-xl ring-1 ring-black/5 p-6 w-80 animate-fadeIn">
        <h3 class="text-lg font-bold text-gray-800 mb-3">Confirm Logout</h3>
        <p class="text-sm text-gray-600 mb-6">Are you sure you want to log out?</p>
        <div class="flex justify-end gap-3">
          <button @click="cancelLogout" class="px-4 py-2 rounded-lg bg-gray-100 hover:bg-gray-200 text-gray-700 font-medium text-sm">Cancel</button>
          <button @click="confirmLogout" class="px-4 py-2 rounded-lg bg-red-600 hover:bg-red-700 text-white font-semibold text-sm">Logout</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
@import 'leaflet/dist/leaflet.css';

@keyframes fadeIn {
  from { opacity: 0; transform: scale(0.95); }
  to   { opacity: 1; transform: scale(1); }
}
.animate-fadeIn { animation: fadeIn 0.2s ease-out; }
</style>

