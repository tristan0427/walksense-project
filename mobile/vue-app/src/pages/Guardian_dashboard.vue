<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import axios from "axios";
import markerPinUrl from '/gps-mark-pin.png'

const router = useRouter()

const alertSound = new Audio('/alert.mp3')

const triggerEmergencyAlarm = () => {
  if (navigator.vibrate) {
    navigator.vibrate([500, 200, 500, 200, 1000])
  }
  alertSound.currentTime = 0
  alertSound.play().catch(e => {
    console.warn('Audio play blocked by browser policy (user must tap screen first):', e)
  })
}
const menuOpen = ref(false)

const closeMenu = () => { menuOpen.value = false }

const handleBackButton = () => {
  if (menuOpen.value) {
    closeMenu()
    history.pushState(null, '', window.location.href)
  }
}
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
const guardianProfile = ref(null)
const distressPulseMarker = ref(null)
const showDismissConfirm = ref(null)
const guardianLocation = ref(null)

const normalIcon = L.icon({
  iconUrl: markerPinUrl,
  iconSize: [40, 40],
  iconAnchor: [20, 40],
  popupAnchor: [0, -40],
})

axios.defaults.baseURL = import.meta.env.VITE_API_BASE_URL
const token = localStorage.getItem('token')
if (token) {
  axios.defaults.headers.common['Authorization'] = `Bearer ${token}`
}

onMounted(async () => {
  const userStr = localStorage.getItem('user')
  if (userStr) {
    guardianProfile.value = JSON.parse(userStr)
  }

  history.pushState(null, '', window.location.href)
  window.addEventListener('popstate', handleBackButton)

  trackGuardianLocation()

  await initializeMap()
  await loadPwdLocations()
  await loadNotifications()

  updateInterval.value = setInterval(() => {
    loadPwdLocations()
    loadNotifications()
  }, 10000)
})

onUnmounted(() => {
  if (updateInterval.value) clearInterval(updateInterval.value)
  window.removeEventListener('popstate', handleBackButton)
})

window._isNavigating = false

const navigateToPwd = (lat, lng, event) => {
  if (window._isNavigating) return
  
  window._isNavigating = true
  
  let btn = null
  if (event && event.target) {
    btn = event.target
    // Store original styles to restore later
    btn.dataset.origBg = btn.style.background
    btn.dataset.origColor = btn.style.color
    btn.dataset.origText = btn.innerText
    
    // Apply Loading State
    btn.disabled = true
    btn.style.background = '#d1d5db'
    btn.style.color = '#4b5563'
    btn.innerHTML = 'Opening Maps...'
  }

  // Fast Fallback: Open Google Maps instantly with only destination
  const destination = `${lat},${lng}`
  window.open(
      `https://www.google.com/maps/dir/?api=1&destination=${destination}&travelmode=driving`,
      '_blank'
  )

  // Reset guard flag and button UI after a short delay
  setTimeout(() => {
    window._isNavigating = false
    if (btn) {
      btn.disabled = false
      btn.style.background = btn.dataset.origBg || '#f7d686'
      btn.style.color = btn.dataset.origColor || '#5a3e00'
      btn.innerHTML = btn.dataset.origText || 'Navigate to PWD'
    }
  }, 1000)
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
    error.value = ''
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
    const fetchedNotifs = response.data.notifications
    
    // Check for new emergency alerts
    if (fetchedNotifs && fetchedNotifs.length > 0) {
      // Find the highest ID which represents the newest notification
      const newestId = Math.max(...fetchedNotifs.map(n => n.id))
      const lastAlertedId = parseInt(localStorage.getItem('last_alerted_id') || '0')
      
      if (newestId > lastAlertedId) {
        triggerEmergencyAlarm()
        localStorage.setItem('last_alerted_id', newestId.toString())
      }
    }

    notifications.value = fetchedNotifs
  } catch(err) {
    console.error('Failed to load notifications:', err)
  }
}

const confirmDismiss = (notifId) => {
  showDismissConfirm.value = notifId
}

const cancelDismiss = () => {
  showDismissConfirm.value = null
}

const isLocatingDistress = ref(false)

const clearSOSView = () => {
  isLocatingDistress.value = false
  if (distressPulseMarker.value && map.value) {
    map.value.removeLayer(distressPulseMarker.value)
    distressPulseMarker.value = null
  }
  markers.value.forEach((marker) => marker.setIcon(normalIcon))
  if (map.value) {
    map.value.closePopup()
  }
}

const deleteNotification = async (id) => {
  showDismissConfirm.value = null
  try {
    await axios.delete(`/api/notifications/${id}`)
    notifications.value = notifications.value.filter(n => n.id !== id)
    clearSOSView()
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
      const marker = L.marker(position, { icon: normalIcon }).addTo(map.value)


      const isOnline = isPwdOnline(pwd)

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
        ${formatLastUpdated(pwd)}
      </p>
    </div>

        <button onclick="window._navigateToPwd(${pwd.location.latitude}, ${pwd.location.longitude}, event)"
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

const isPwdOnline = (pwd) => {
  if (!pwd || !pwd.location) return false

  // Use server relative seconds_ago if available
  if (pwd.seconds_ago !== undefined && pwd.seconds_ago !== null) {
    return pwd.seconds_ago < 600 // 10 minutes
  }

  // Fallback to client-side system clock calculation
  if (!pwd.location.last_updated) return false
  const lastUpdate = new Date(pwd.location.last_updated)
  const now = new Date()
  const diffMinutes = (now.getTime() - lastUpdate.getTime()) / 60000
  return diffMinutes < 10 // 10 minutes fallback
}

const formatLastUpdated = (pwd) => {
  if (pwd && pwd.seconds_ago !== undefined && pwd.seconds_ago !== null) {
    const diffMins = Math.floor(pwd.seconds_ago / 60)
    if (diffMins < 1) return 'Just now'
    if (diffMins < 60) return `${diffMins} min ago`
    const diffHours = Math.floor(diffMins / 60)
    if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`
  }

  if (!pwd || !pwd.location || !pwd.location.last_updated) return ''
  const date = new Date(pwd.location.last_updated)
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

const refreshLocations = () => {
  loading.value = true
  loadPwdLocations()
}

const trackGuardianLocation = () => {
  if (!navigator.geolocation) return
  navigator.geolocation.watchPosition(
    (pos) => {
      guardianLocation.value = {
        lat: pos.coords.latitude,
        lng: pos.coords.longitude,
      }
    },
    () => {},
    { enableHighAccuracy: false, maximumAge: 30000 }
  )
}

const formatAlertTime = (dateString) => {
  const date = new Date(dateString)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)

  if (diffMins < 1) return 'Just now'
  if (diffMins < 60) return `${diffMins} min ago`

  const diffHours = Math.floor(diffMins / 60)
  if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`

  const diffDays = Math.floor(diffHours / 24)
  return `${diffDays} day${diffDays > 1 ? 's' : ''} ago`
}

const isOldAlert = (dateString) => {
  const date = new Date(dateString)
  const now = new Date()
  return (now.getTime() - date.getTime()) > 600000
}

const getDistanceToNotif = (notif) => {
  if (!guardianLocation.value || !notif.latitude || !notif.longitude) return null
  const R = 6371
  const dLat = (parseFloat(notif.latitude) - guardianLocation.value.lat) * Math.PI / 180
  const dLng = (parseFloat(notif.longitude) - guardianLocation.value.lng) * Math.PI / 180
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(guardianLocation.value.lat * Math.PI / 180) *
    Math.cos(parseFloat(notif.latitude) * Math.PI / 180) *
    Math.sin(dLng / 2) ** 2
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  const km = R * c
  return km < 1 ? `${Math.round(km * 1000)}m away` : `${km.toFixed(1)}km away`
}

const createSOSIcon = () => {
  return L.divIcon({
    className: '',
    html: `<svg width="44" height="44" viewBox="0 0 44 44" xmlns="http://www.w3.org/2000/svg">
      <circle cx="22" cy="22" r="18" fill="#dc2626" stroke="#fff" stroke-width="2.5"/>
      <text x="22" y="29" text-anchor="middle" fill="white" font-size="24" font-weight="bold" font-family="sans-serif">!</text>
    </svg>`,
    iconSize: [44, 44],
    iconAnchor: [22, 22],
    popupAnchor: [0, -22],
  })
}

const locateDistressOnMap = (notif) => {
  isLocatingDistress.value = true
  if (!map.value || !notif.latitude || !notif.longitude) return

  const lat = parseFloat(notif.latitude)
  const lng = parseFloat(notif.longitude)

  map.value.setView([lat, lng], 18)

  if (distressPulseMarker.value) {
    map.value.removeLayer(distressPulseMarker.value)
  }
  const pulseIcon = L.divIcon({
    className: '',
    html: '<div class="sonar-pulse"></div>',
    iconSize: [60, 60],
    iconAnchor: [30, 30],
  })
  distressPulseMarker.value = L.marker([lat, lng], { icon: pulseIcon, interactive: false }).addTo(map.value)

  let closestMarker = null
  let closestDist = Infinity
  markers.value.forEach((marker) => {
    const pos = marker.getLatLng()
    const dist = Math.abs(pos.lat - lat) + Math.abs(pos.lng - lng)
    if (dist < closestDist) {
      closestDist = dist
      closestMarker = marker
    }
  })

  if (closestMarker) {
    closestMarker.setIcon(createSOSIcon())
    closestMarker.openPopup()
  }
}
</script>

<template>
  <div class="min-h-screen bg-[#fafaf7] flex flex-col">

    <!-- Header -->
    <header class="bg-[#f7d686] border-b border-yellow-300 px-4 pb-3 flex items-center justify-between drop-shadow-sm" style="padding-top: calc(env(safe-area-inset-top, 0px) + 12px);">
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

    <div v-if="menuOpen" class="fixed inset-0 z-40" @click="closeMenu" />

    <div v-if="menuOpen" class="absolute right-4 mt-2 w-64 bg-white rounded-xl shadow-lg ring-1 ring-black/5 z-50 overflow-hidden transition-all duration-150">
      <div class="px-4 py-4 border-b border-gray-100 bg-gray-50/50">
        <div class="flex items-center gap-3 mb-3">
          <div class="w-10 h-10 rounded-full bg-yellow-200 text-yellow-700 flex items-center justify-center font-bold text-lg shrink-0">
            {{ guardianProfile?.name?.charAt(0)?.toUpperCase() || 'G' }}
          </div>
          <div class="overflow-hidden">
            <p class="text-sm font-bold text-gray-800 truncate">{{ guardianProfile?.name || 'Guardian Profile' }}</p>
            <p class="text-xs text-gray-500 truncate">{{ guardianProfile?.email || '' }}</p>
          </div>
        </div>

        <div class="mt-3 pt-3 border-t border-gray-100">
          <p class="text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-1">Monitoring</p>
          <p class="text-sm font-medium text-gray-700 truncate">
            {{ pwdLocations.length > 0 ? pwdLocations[0].pwd_name : 'No PWD assigned' }}
          </p>
        </div>
      </div>

      <button @click="logout" class="block w-full text-left px-4 py-3 text-red-600 font-semibold hover:bg-red-50 text-sm flex items-center gap-2">
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"/></svg>
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
              <span v-if="pwd.location && isPwdOnline(pwd)" class="relative flex h-3 w-3">
                <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                <span class="relative inline-flex rounded-full h-3 w-3 bg-green-500"></span>
              </span>
              <span v-else-if="pwd.location" class="h-3 w-3 rounded-full bg-gray-400"></span>

              <span :class="[
                'px-2 py-1 rounded-full text-xs font-semibold',
                pwd.location && isPwdOnline(pwd)
                  ? 'bg-green-100 text-green-800'
                  : 'bg-gray-100 text-gray-800'
              ]">
                {{ pwd.location && isPwdOnline(pwd) ? 'Online' : 'Offline' }}
              </span>
            </div>
          </div>

          <div v-if="pwd.location">
            <p class="text-xs text-gray-500">
              Last Updated: {{ formatLastUpdated(pwd) }}
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
            <div v-if="!isPwdOnline(pwd)"
                 class="mt-2 p-2 bg-yellow-50 border border-yellow-200 rounded-lg flex items-start gap-2">
              <svg class="w-4 h-4 text-yellow-600 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
              </svg>
              <p class="text-xs font-medium text-yellow-800">Device appears offline. Last location from {{ formatLastUpdated(pwd) }}.</p>
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
        <div class="relative h-80 lg:h-96 w-full rounded-xl overflow-hidden shadow-inner bg-gray-50">
          <div ref="mapContainer" class="absolute inset-0 z-10"></div>
          
          <div v-if="isLocatingDistress" class="absolute top-4 left-1/2 -translate-x-1/2 z-[1000]">
            <button @click="clearSOSView" 
                    class="px-4 py-2 bg-white text-gray-800 font-bold text-xs tracking-wide rounded-full shadow-lg border border-gray-200 flex items-center gap-2 hover:bg-gray-50 transition-all">
              <span class="text-red-500 text-sm leading-none">&times;</span> Return to Normal View
            </button>
          </div>
        </div>
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
               :class="[
                 'rounded-xl p-3 shadow-sm transition-all hover:shadow-md animate-fadeIn border',
                 isOldAlert(notif.triggered_at)
                   ? 'border-gray-200 bg-gray-50'
                   : 'border-red-200 bg-red-50'
               ]">
            <div class="flex justify-between items-start">
              <div>
                <h3 :class="[
                  'text-sm font-bold flex items-center gap-2',
                  isOldAlert(notif.triggered_at) ? 'text-gray-500' : 'text-red-700'
                ]">
                  <svg class="w-4 h-4" :class="isOldAlert(notif.triggered_at) ? 'text-gray-400' : 'text-red-600'" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
                  </svg>
                  {{ isOldAlert(notif.triggered_at) ? 'Past Alert' : 'Distress Signal' }}
                </h3>
                <p class="text-xs text-gray-600 font-medium tracking-wide mt-1">{{ notif.pwd?.user?.name || 'Your PWD' }}</p>
                <p class="text-[10px] mt-1 uppercase tracking-wide" :class="isOldAlert(notif.triggered_at) ? 'text-gray-400' : 'text-red-400 font-semibold'">
                  {{ formatAlertTime(notif.triggered_at) }}
                </p>
                <p v-if="getDistanceToNotif(notif)" class="text-[10px] text-gray-500 mt-0.5 font-medium">
                  {{ getDistanceToNotif(notif) }} from you
                </p>
              </div>
            </div>

            <div class="mt-3 flex gap-2">
              <button @click="locateDistressOnMap(notif)"
                      v-if="notif.latitude && notif.longitude"
                      :class="[
                        'flex-1 py-1.5 px-3 text-xs font-semibold rounded-lg transition-colors',
                        isOldAlert(notif.triggered_at)
                          ? 'bg-gray-500 text-white hover:bg-gray-600'
                          : 'bg-red-600 text-white hover:bg-red-700'
                      ]">
                Locate on Map
              </button>
              <button @click="confirmDismiss(notif.id)"
                      class="py-1.5 px-3 text-xs font-medium rounded-lg text-gray-500 hover:text-red-700 hover:bg-red-100 transition-colors">
                Dismiss
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Dismiss Confirm Modal -->
    <div v-if="showDismissConfirm" class="fixed inset-0 bg-black/40 backdrop-blur-sm z-[9998]" @click="cancelDismiss"></div>
    <div v-if="showDismissConfirm" class="fixed inset-0 flex items-center justify-center z-[9999]">
      <div class="bg-white rounded-2xl shadow-xl ring-1 ring-black/5 p-6 w-80 animate-fadeIn">
        <div class="flex items-center gap-3 mb-4">
          <div class="w-10 h-10 rounded-full bg-red-100 flex items-center justify-center shrink-0">
            <svg class="w-5 h-5 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
            </svg>
          </div>
          <h3 class="text-lg font-bold text-gray-800">Dismiss Alert?</h3>
        </div>
        <p class="text-sm text-gray-600 mb-6">Are you sure you want to dismiss this emergency alert? This action cannot be undone.</p>
        <div class="flex justify-end gap-3">
          <button @click="cancelDismiss" class="px-4 py-2 rounded-lg bg-gray-100 hover:bg-gray-200 text-gray-700 font-medium text-sm">Keep Alert</button>
          <button @click="deleteNotification(showDismissConfirm)" class="px-4 py-2 rounded-lg bg-red-600 hover:bg-red-700 text-white font-semibold text-sm">Dismiss</button>
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

@keyframes sonarPulse {
  0%   { transform: scale(0.5); opacity: 0.8; }
  100% { transform: scale(2.5); opacity: 0; }
}
.sonar-pulse {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  background: rgba(220, 38, 38, 0.3);
  animation: sonarPulse 1.5s ease-out infinite;
}
</style>

