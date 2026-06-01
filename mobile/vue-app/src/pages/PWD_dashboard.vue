<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router'
import { TextToSpeech } from '@capacitor-community/text-to-speech';
import { CapacitorHttp } from '@capacitor/core';
import { App } from '@capacitor/app';
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

const isNetworkOnline = ref(navigator.onLine);
const updateNetworkStatus = () => {
  isNetworkOnline.value = navigator.onLine;
};

// ── GPS Retry Config ─────────────────────────────────────────────────────────
const GPS_MAX_RETRIES = 10;
const GPS_RETRY_INTERVALS = [3000, 5000, 5000, 10000, 10000, 15000, 15000, 20000, 30000, 30000];
const gpsRetryCount = ref(0);
let gpsRetryTimer = null;
let appStateListener = null;
const user = ref(null);
const guardian = ref(null);
const menuOpen = ref(false);
const showLogoutConfirm = ref(false);

const closeMenu = () => { menuOpen.value = false; };

const handleBackButton = () => {
  if (menuOpen.value) {
    closeMenu();
    history.pushState(null, '', window.location.href);
  }
};

// ── Camera connection state ──────────────────────────────────────────────────
const dayCamConnected   = ref(false);
const nightCamConnected = ref(false);
const dayCamStatus      = ref('Not Connected');
const nightCamStatus    = ref('Not Connected');

const dayCamIp   = ref(localStorage.getItem('dayCamIp')   || '');
const nightCamIp = ref(localStorage.getItem('nightCamIp') || '');

const activeCamera  = ref('day');
const currentLux    = ref(null);
const averageLux    = ref(null);

// ── Lux Hysteresis Config ─────────────────────────────────────────
const NIGHT_ENTER    = 5;
const NIGHT_EXIT     = 15;
const LUX_BUFFER_SIZE = 10;
const SUSTAINED_MS   = 3000;

const luxBuffer      = [];
let sustainedSince   = null;
let sustainedTarget  = null;


// ── Camera Fault Tolerance ────────────────────────────────────────────────────
const OFFLINE_FAILURE_THRESHOLD = 5;
let dayPollFailures   = 0;
let nightPollFailures = 0;
let dayReconnectTimeout   = null;
let nightReconnectTimeout = null;

const isSendingDistress = ref(false);
const distressSent = ref(false);
const distressFailed = ref(false);
const distressErrorMessage = ref('');
const isDistressSpeaking = ref(false);

// ── Detection state ──────────────────────────────────────────────────────────
const nearestObject     = ref(null);
const detectionInterval = ref(null);
const lastSpokenTime    = ref(0);
const isSpeaking        = ref(false);
const currentSpokenClass = ref(null);
const isPathClearAnnounced = ref(false);
let clearPathSince = null;
const stationaryAnnouncedClasses = new Set();

// ── Demo Mode ────────────────────────────────────────────────────────────────
// Shows camera preview + bounding box overlay when ON. Zero overhead when OFF.
const demoMode = ref(false);
const previewCanvas = ref(null);
let previewListener = null;

const perfMetrics = ref({
  detectCalls: 0,
  avgDetectMs: 0,
  avgInferenceMs: 0,
});

const TIER_1_CLASSES = new Set(['pothole', 'glass wall', 'stairs', 'puddle']);
const TIER_2_CLASSES = new Set(['car', 'tricycle', 'motorcycle', 'truck', 'bus', 'person', 'dog']);
const TIER_3_CLASSES = new Set([
  'pole', 'tree', 'bench', 'trash can', 'fence', 'standing aircon', 'bollards',
  'chair', 'table', 'couch', 'cabinet', 'refrigerator', 'stall'
]);
const TIER_4_CLASSES = new Set(['gate', 'door', 'window', 'wall']);

const IMMINENT_COOLDOWN_MS = 3500;

const TIER_COOLDOWN_MS = {
  tier1: 3000,
  tier2: 1800,
  tier3: 4000,
  tier4: 5000,
};

const lastSpokenByClass = new Map();



// ── Lifecycle ────────────────────────────────────────────────────────────────
onMounted(async () => {
  console.log('PWD Dashboard mounted');

  history.pushState(null, '', window.location.href);
  window.addEventListener('popstate', handleBackButton);
  window.addEventListener('online', updateNetworkStatus);
  window.addEventListener('offline', updateNetworkStatus);

  ObjectDetection.addListener('dayCamConnected', () => {
    dayCamConnected.value = true;
    dayCamStatus.value    = 'Connected';
    dayPollFailures = 0;
    console.log('✓ Day cam connected');
    maybeStartDetection();
  });
  ObjectDetection.addListener('dayCamError', (data) => {
    console.warn('Day cam stream error (will retry):', data.error);
    dayCamConnected.value = false;
    dayCamStatus.value = 'Offline';
    // Clear detection display if no camera is connected
    if (!nightCamConnected.value) {
      nearestObject.value = null;
    }
    attemptDayCamReconnect();
  });

  ObjectDetection.addListener('nightCamConnected', () => {
    nightCamConnected.value = true;
    nightCamStatus.value    = 'Connected';
    nightPollFailures = 0;
    console.log('✓ Night cam connected');
    maybeStartDetection();
  });
  ObjectDetection.addListener('nightCamError', (data) => {
    console.warn('Night cam stream error (will retry):', data.error);
    nightCamConnected.value = false;
    nightCamStatus.value = 'Offline';
    // Clear detection display if no camera is connected
    if (!dayCamConnected.value) {
      nearestObject.value = null;
    }
    attemptNightCamReconnect();
  });

  // Load model — diagnostics mode is permanently disabled
  try {
    await ObjectDetection.loadModel();
    console.log('✓ Model loaded');
  } catch (err) {
    console.error('Model load failed:', err);
  }

  const userStr = localStorage.getItem('user');
  if (userStr) user.value = JSON.parse(userStr);

  const guardianStr = localStorage.getItem('guardian');
  if (guardianStr) guardian.value = JSON.parse(guardianStr);

  if (dayCamIp.value || nightCamIp.value) {
    await connectBothCameras();
  } else {
    await autoDiscoverCameras();
  }

  if (dayCamIp.value) {
    startLuxPolling();
  }

  if (nightCamIp.value) {
    startEmergencyPolling();
  }

  // Listen for app resume to re-check GPS (e.g. user toggled Location in settings)
  try {
    appStateListener = await App.addListener('appStateChange', async ({ isActive }) => {
      if (isActive && !isTracking.value) {
        console.log('App resumed — retrying GPS tracking…');
        clearGpsRetryTimer();
        gpsRetryCount.value = 0;
        await startTracking();
      }
    });
  } catch (e) {
    console.log('App state listener not available (browser mode)');
  }

  await startTracking();
});

onUnmounted(async () => {
  window.removeEventListener('popstate', handleBackButton);
  window.removeEventListener('online', updateNetworkStatus);
  window.removeEventListener('offline', updateNetworkStatus);
  stopDetection();
  stopLuxPolling();
  stopEmergencyPolling();
  clearGpsRetryTimer();
  if (appStateListener) {
    appStateListener.remove();
    appStateListener = null;
  }
  if (dayReconnectTimeout)   clearTimeout(dayReconnectTimeout);
  if (nightReconnectTimeout) clearTimeout(nightReconnectTimeout);
  // Kill demo preview if still active
  if (demoMode.value) {
    try { await ObjectDetection.stopPreview(); } catch (e) {}
    if (previewListener) { previewListener.remove(); previewListener = null; }
  }
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

  if (nightCamIp.value) {
    try {
      await ObjectDetection.startNightStream({ ip: nightCamIp.value });
    } catch (err) {
      console.error('Night cam start failed:', err);
      nightCamStatus.value = 'Failed to start';
    }
  }
};

const autoDiscoverCameras = async () => {
  try {
    console.log('[AutoDiscovery] No saved IPs — scanning hotspot...');
    const result = await ObjectDetection.scanHotspotNetwork({
      port: 82,
      path: '/identity',
      timeout: 2500,
    });
    if (result.success && result.boards) {
      for (const b of result.boards) {
        if (b.board === 'day' && b.ip) {
          dayCamIp.value = b.ip;
          localStorage.setItem('dayCamIp', b.ip);
          console.log(`[AutoDiscovery] ✅ Day camera at ${b.ip}`);
        }
        if (b.board === 'night' && b.ip) {
          nightCamIp.value = b.ip;
          localStorage.setItem('nightCamIp', b.ip);
          console.log(`[AutoDiscovery] ✅ Night camera at ${b.ip}`);
        }
      }
      if (dayCamIp.value || nightCamIp.value) {
        await connectBothCameras();
      }
    }
  } catch (err) {
    console.log('[AutoDiscovery] Scan failed:', err.message);
  }
};

// ── Light Sensor Handling ───────────────────────────────────────────────────

let luxInterval = null;

const startLuxPolling = () => {
  if (luxInterval) return;
  if (!dayCamIp.value) return;

  luxInterval = setInterval(async () => {
    try {
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
        dayPollFailures = 0;
        onLuxReceived(data.lux);
      }
    } catch (err) {
      dayPollFailures++;
      if (dayPollFailures >= OFFLINE_FAILURE_THRESHOLD) {
        dayCamConnected.value = false;
        dayCamStatus.value    = 'Offline';
        dayPollFailures = 0;
        attemptDayCamReconnect();
      }
    }
  }, 500);
};

const stopLuxPolling = () => {
  if (luxInterval) {
    clearInterval(luxInterval);
    luxInterval = null;
  }
};

const onLuxReceived = async (lux) => {
  currentLux.value = lux;

  luxBuffer.push(lux);
  if (luxBuffer.length > LUX_BUFFER_SIZE) luxBuffer.shift();

  if (luxBuffer.length < 3) return;

  const avg = luxBuffer.reduce((sum, v) => sum + v, 0) / luxBuffer.length;
  averageLux.value = Math.round(avg * 10) / 10;

  let targetMode = null;

  if (activeCamera.value === 'day' && avg < NIGHT_ENTER) {
    targetMode = 'night';
  } else if (activeCamera.value === 'night' && avg > NIGHT_EXIT) {
    targetMode = 'day';
  }

  if (targetMode) {
    if (sustainedTarget !== targetMode) {
      sustainedTarget = targetMode;
      sustainedSince  = Date.now();
    }

    const elapsed = Date.now() - sustainedSince;
    if (elapsed >= SUSTAINED_MS) {
      activeCamera.value = targetMode;
      console.log(`💡 Avg lux ${avg.toFixed(1)} sustained ${(elapsed/1000).toFixed(1)}s → switching to ${targetMode} camera`);
      sustainedTarget = null;
      sustainedSince  = null;

      try {
        await ObjectDetection.switchActiveCamera({ camera: targetMode });
        maybeStartDetection();
      } catch (err) {
        console.error('Camera switch failed:', err);
      }
    }
  } else {
    sustainedTarget = null;
    sustainedSince  = null;
  }
};

// ── Emergency Button Handling ───────────────────────────────────────────────

let emergencyInterval = null;
let lastHardwarePress = 0;

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
        const now = Date.now();
        if (now - lastHardwarePress >= 5000) {
          lastHardwarePress = now;
          sendDistressSignal('hardware_button');
        }
      }
      nightPollFailures = 0;
    } catch (err) {
      nightPollFailures++;
      if (nightPollFailures >= OFFLINE_FAILURE_THRESHOLD) {
        nightCamConnected.value = false;
        nightCamStatus.value    = 'Offline';
        nightPollFailures = 0;
        attemptNightCamReconnect();
      }
    }
  }, 500);
};

const stopEmergencyPolling = () => {
  if (emergencyInterval) {
    clearInterval(emergencyInterval);
    emergencyInterval = null;
  }
};

// ── Silent Reconnect Helpers ────────────────────────────────────────────────

const attemptDayCamReconnect = () => {
  if (dayReconnectTimeout) return;
  dayReconnectTimeout = setTimeout(async () => {
    dayReconnectTimeout = null;
    if (!dayCamIp.value) return;
    try {
      await ObjectDetection.startDayStream({ ip: dayCamIp.value });
    } catch (err) {
      console.warn('Day cam reconnect failed:', err.message);
      dayCamConnected.value = false;
      dayCamStatus.value    = 'Offline';
    }
  }, 5000);
};

const attemptNightCamReconnect = () => {
  if (nightReconnectTimeout) return;
  nightReconnectTimeout = setTimeout(async () => {
    nightReconnectTimeout = null;
    if (!nightCamIp.value) return;
    try {
      await ObjectDetection.startNightStream({ ip: nightCamIp.value });
    } catch (err) {
      console.warn('Night cam reconnect failed:', err.message);
      nightCamConnected.value = false;
      nightCamStatus.value    = 'Offline';
    }
  }, 5000);
};

const sendDistressSignal = async (source = 'app_button') => {
  if (isSendingDistress.value) return;

  isSendingDistress.value = true;
  distressFailed.value = false;
  distressErrorMessage.value = '';

  isDistressSpeaking.value = true;
  try { await TextToSpeech.speak({ text: 'Sending emergency signal.', lang: 'en-US' }); } catch (e) {}
  isDistressSpeaking.value = false;

  try {
    let lat = null;
    let lng = null;

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

    distressSent.value = true;
    setTimeout(() => {
      distressSent.value = false;
    }, 4000);

    isDistressSpeaking.value = true;
    try {
      await TextToSpeech.speak({
        text: 'Emergency distress signal sent to your guardian.',
        lang: 'en-US',
      });
    } catch (e) {}
    isDistressSpeaking.value = false;

  } catch (err) {
    console.error('Failed to send distress signal:', err);

    let spokenError;
    const msg = err.message || '';
    const status = err.response?.status;

    if (msg.includes('Network Error') || !status) {
      spokenError = 'Failed to send distress signal. Please check your internet connection and try again.';
    } else if (status === 500) {
      spokenError = 'Failed to send distress signal. The server is currently unavailable. Please try again later.';
    } else if (status === 401 || status === 403) {
      spokenError = 'Failed to send distress signal. Your session may have expired. Please log in again.';
    } else {
      spokenError = 'Failed to send distress signal. An unknown error occurred. Please try again.';
    }

    distressFailed.value = true;
    distressErrorMessage.value = spokenError;
    setTimeout(() => { distressFailed.value = false; }, 8000);

    isDistressSpeaking.value = true;
    try { await TextToSpeech.speak({ text: spokenError, lang: 'en-US' }); } catch (e) {}
    isDistressSpeaking.value = false;

  } finally {
    isSendingDistress.value = false;
    isDistressSpeaking.value = false;
  }
};

// ── Detection ────────────────────────────────────────────────────────────────

const maybeStartDetection = () => {
  if (detectionInterval.value) return;
  if (dayCamConnected.value || nightCamConnected.value) startDetection();
};

const startDetection = () => {
  console.log('Starting detection loop');
  detectionInterval.value = setInterval(async () => {
    try {
      const result = await ObjectDetection.detectFromStream({ confidence: 0.317 });

      updatePerfMetrics(result.metrics);
      const now = Date.now();

      if (result.nearest) {
        isPathClearAnnounced.value = false;
        clearPathSince = null;
        nearestObject.value = result.nearest;

        const { class: objClass, distance, direction, avoidance } = result.nearest;
        const isStable   = result.nearest.stable === true;
        const isImminent = distance === 'imminent';
        const tier = getClassTier(objClass);
        const canSpeak = canSpeakClass(tier, objClass, now);
        
        const lastClassSpokenAt = lastSpokenByClass.get(objClass) || 0;
        const imminentCanSpeak = (now - lastClassSpokenAt) >= IMMINENT_COOLDOWN_MS;
        const shouldHandleTTS = isImminent ? imminentCanSpeak : canSpeak;

        if (!isStable) {
          stationaryAnnouncedClasses.clear();
        } else if (!isImminent && stationaryAnnouncedClasses.has(objClass)) {
          return;
        }

        if (shouldHandleTTS && !isDistressSpeaking.value) {
          const shouldSpeak = shouldSpeakAlert(tier, direction, distance, isImminent);

          if (shouldSpeak) {
            let msg;
            let msg2 = null;
            let ttsRate;
            let ttsRate2 = 0.9;

            if (isImminent) {
              const locationPhrase = direction === 'ahead'
                  ? 'directly ahead'
                  : `on your ${direction}`;
              
              msg = `Warning! ${objClass} ${locationPhrase}, stop!`;
              ttsRate = 1.25;

              if (avoidance === 'blocked') {
                msg2 = `Path is blocked. Slowly turn left or right to find a clear path.`;
              } else if (avoidance === 'left') {
                msg2 = `Move to your left.`;
              } else if (avoidance === 'right') {
                msg2 = `Move to your right.`;
              } else if (avoidance === 'both') {
                msg2 = `Move left or right.`;
              }
            } else {
              const locationPhrase = direction === 'ahead'
                  ? 'ahead'
                  : `on your ${direction}`;

              let guidancePhrase = '';
              if (direction === 'ahead' && avoidance && avoidance !== 'blocked') {
                if (avoidance === 'left')       guidancePhrase = '. Move to your left.';
                else if (avoidance === 'right') guidancePhrase = '. Move to your right.';
                else if (avoidance === 'both')  guidancePhrase = '. Move left or right.';
              } else if (direction === 'ahead' && avoidance === 'blocked') {
                guidancePhrase = '. Path is blocked. Slowly turn left or right to find a clear path.';
              }

              msg = `${objClass} ${distance} ${locationPhrase}${guidancePhrase}`;
              ttsRate = 0.9;
            }

            if (isSpeaking.value) {
              if (tier === 'tier1' && currentSpokenClass.value !== objClass) {
                try { await TextToSpeech.stop(); } catch (e) {}
              } else {
                return;
              }
            }

            isSpeaking.value = true;
            currentSpokenClass.value = objClass;
            if (isStable) stationaryAnnouncedClasses.add(objClass);
            lastSpokenTime.value = Date.now();
            lastSpokenByClass.set(objClass, lastSpokenTime.value);

            TextToSpeech.speak({
              text: msg,
              lang: 'en-US',
              rate: ttsRate,
              pitch: 1.0,
              volume: 1.0
            }).then(async () => {
              if (msg2) {
                await new Promise(resolve => setTimeout(resolve, 150));
                if (!isSpeaking.value) return;
                await TextToSpeech.speak({
                  text: msg2,
                  lang: 'en-US',
                  rate: ttsRate2,
                  pitch: 1.0,
                  volume: 1.0
                });
              }
            }).catch((ttsError) => {
              console.error('TTS error:', ttsError);
            }).finally(() => {
              isSpeaking.value = false;
              currentSpokenClass.value = null;
            });
          }
        }
      } else {
        nearestObject.value = null;

        if (clearPathSince === null) {
          clearPathSince = Date.now();
        }

        const elapsedClearTime = Date.now() - clearPathSince;
        if (elapsedClearTime >= 1500) {
          if (!isPathClearAnnounced.value && !isSpeaking.value && !isDistressSpeaking.value) {
            isSpeaking.value = true;
            isPathClearAnnounced.value = true;
            TextToSpeech.speak({
              text: 'Clear ahead',
              lang: 'en-US',
              rate: 1.0,
              pitch: 1.0,
              volume: 1.0
            }).catch((ttsError) => {
              console.error('TTS error:', ttsError);
              isPathClearAnnounced.value = false;
            }).finally(() => {
              isSpeaking.value = false;
            });
          }
        }
      }
    } catch (err) {
      if (!err.message?.includes('No valid')) {
        console.error('Detection error:', err);
      }
      isSpeaking.value = false;
      currentSpokenClass.value = null;
    }
  }, 300);
};

const stopDetection = () => {
  if (detectionInterval.value) clearInterval(detectionInterval.value);
  detectionInterval.value = null;
  isPathClearAnnounced.value = false;
  clearPathSince = null;
};

const getClassTier = (className) => {
  if (TIER_1_CLASSES.has(className)) return 'tier1';
  if (TIER_2_CLASSES.has(className)) return 'tier2';
  if (TIER_3_CLASSES.has(className)) return 'tier3';
  if (TIER_4_CLASSES.has(className)) return 'tier4';
  return 'tier3';
};

const canSpeakClass = (tier, className, now) => {
  const lastClassSpokenAt = lastSpokenByClass.get(className) || 0;
  const cooldown = TIER_COOLDOWN_MS[tier] ?? 4000;
  return now - lastClassSpokenAt >= cooldown;
};

const shouldSpeakAlert = (tier, direction, distance, isImminent) => {
  if (isImminent) return true;
  if (tier === 'tier1') return distance !== 'far';
  if (tier === 'tier2') return direction === 'ahead' || distance === 'very close';
  if (tier === 'tier3') return direction === 'ahead' && distance !== 'far';
  return direction === 'ahead' && (distance === 'close' || distance === 'very close');
};

const updatePerfMetrics = (metrics) => {
  if (!metrics) return;
  perfMetrics.value.detectCalls += 1;
  const n = perfMetrics.value.detectCalls;
  perfMetrics.value.avgDetectMs = Math.round(
      ((perfMetrics.value.avgDetectMs * (n - 1)) + (metrics.detectMs || 0)) / n
  );
  perfMetrics.value.avgInferenceMs = Math.round(
      ((perfMetrics.value.avgInferenceMs * (n - 1)) + (metrics.inferenceMs || 0)) / n
  );
};

// ── GPS ───────────────────────────────────────────────────────────────────────
const startTracking = async () => {
  try {
    error.value = '';
    await LocationService.startTracking();
    isTracking.value = true;
    gpsRetryCount.value = 0;
    clearGpsRetryTimer();
    const position = await LocationService.getCurrentLocation();
    currentLocation.value = position.coords;
  } catch (err) {
    console.error('Tracking error:', err);
    isTracking.value = false;
    scheduleGpsRetry();
  }
};

const scheduleGpsRetry = () => {
  if (gpsRetryCount.value >= GPS_MAX_RETRIES) {
    error.value = 'GPS unavailable after multiple attempts. Please enable Location/GPS in your phone settings and tap Retry.';
    return;
  }

  const delay = GPS_RETRY_INTERVALS[Math.min(gpsRetryCount.value, GPS_RETRY_INTERVALS.length - 1)];
  const secondsLeft = Math.round(delay / 1000);
  error.value = `GPS connecting… attempt ${gpsRetryCount.value + 1}/${GPS_MAX_RETRIES} (retrying in ${secondsLeft}s)`;

  gpsRetryTimer = setTimeout(async () => {
    gpsRetryCount.value++;
    try {
      await LocationService.startTracking();
      isTracking.value = true;
      error.value = '';
      gpsRetryCount.value = 0;
      const position = await LocationService.getCurrentLocation();
      currentLocation.value = position.coords;
    } catch (retryErr) {
      console.warn(`GPS retry ${gpsRetryCount.value} failed:`, retryErr.message || retryErr);
      isTracking.value = false;
      scheduleGpsRetry();
    }
  }, delay);
};

const clearGpsRetryTimer = () => {
  if (gpsRetryTimer) {
    clearTimeout(gpsRetryTimer);
    gpsRetryTimer = null;
  }
};

const retryTracking = async () => {
  clearGpsRetryTimer();
  gpsRetryCount.value = 0;
  error.value = '';
  await startTracking();
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

const goToWearableSetup = () => {
  router.push('/wearable-setup')
}

// ── Demo Mode Toggle ─────────────────────────────────────────────────────────
const toggleDemoMode = async () => {
  demoMode.value = !demoMode.value;
  if (demoMode.value) {
    // Start preview emitter in Java
    try {
      await ObjectDetection.startPreview();
      console.log('✓ Demo preview started');
    } catch (err) {
      console.error('Failed to start preview:', err);
      demoMode.value = false;
    }
    // Listen for preview frames from Java
    previewListener = await ObjectDetection.addListener('previewFrame', (data) => {
      if (!data.frame || !previewCanvas.value) return;
      const img = new Image();
      img.onload = () => {
        const canvas = previewCanvas.value;
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        drawBoundingBox(ctx, canvas.width, canvas.height);
        drawFpsOverlay(ctx);
      };
      img.src = data.frame;
    });
  } else {
    // Stop preview emitter
    try {
      await ObjectDetection.stopPreview();
      console.log('✓ Demo preview stopped');
    } catch (err) {
      console.error('Failed to stop preview:', err);
    }
    // Remove listener
    if (previewListener) {
      previewListener.remove();
      previewListener = null;
    }
    // Clear canvas
    if (previewCanvas.value) {
      const ctx = previewCanvas.value.getContext('2d');
      ctx.clearRect(0, 0, previewCanvas.value.width, previewCanvas.value.height);
    }
  }
  closeMenu();
};

const drawBoundingBox = (ctx, canvasW, canvasH) => {
  if (!nearestObject.value) return;
  const { x1, y1, x2, y2 } = nearestObject.value;
  if (x1 === undefined) return;
  const cls = nearestObject.value.class;
  const conf = nearestObject.value.confidence;

  // Scale 320x320 model coords to canvas dimensions
  const scaleX = canvasW / 320;
  const scaleY = canvasH / 240;
  const sx1 = x1 * scaleX, sy1 = y1 * scaleY;
  const sx2 = x2 * scaleX, sy2 = y2 * scaleY;

  // Draw box
  ctx.strokeStyle = '#f59e0b';
  ctx.lineWidth = 2;
  ctx.strokeRect(sx1, sy1, sx2 - sx1, sy2 - sy1);

  // Draw label background
  const label = `${cls} ${(conf * 100).toFixed(0)}%`;
  ctx.font = 'bold 11px sans-serif';
  const textW = ctx.measureText(label).width;
  ctx.fillStyle = 'rgba(245, 158, 11, 0.85)';
  ctx.fillRect(sx1, sy1 - 16, textW + 8, 16);

  // Draw label text
  ctx.fillStyle = '#fff';
  ctx.fillText(label, sx1 + 4, sy1 - 4);
};

const drawFpsOverlay = (ctx) => {
  const inf = perfMetrics.value.avgInferenceMs;
  const det = perfMetrics.value.avgDetectMs;
  const label = `${inf}ms inference / ${det}ms total`;
  ctx.font = '10px sans-serif';
  ctx.fillStyle = 'rgba(0,0,0,0.5)';
  ctx.fillRect(2, 2, ctx.measureText(label).width + 8, 14);
  ctx.fillStyle = '#0f0';
  ctx.fillText(label, 6, 12);
};
</script>

<template>
  <div class="min-h-screen bg-[#fafaf7] flex flex-col">

    <!-- Header -->
    <header class="bg-[#f7d686] border-b border-yellow-300 px-4 pb-3 flex items-center justify-between drop-shadow-sm" style="padding-top: calc(env(safe-area-inset-top, 0px) + 12px);">
      <div class="flex items-center gap-2">
        <h1 class="text-sm font-black tracking-widest uppercase text-gray-800">WALKSENSE</h1>
        <span v-if="demoMode" class="text-[9px] font-bold tracking-wider uppercase px-1.5 py-0.5 rounded bg-amber-500 text-white animate-pulse">DEMO</span>
      </div>
      <button @click="menuOpen = !menuOpen" class="p-1 rounded-lg hover:bg-yellow-400/40 transition">
        <svg class="w-6 h-6 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16"/>
        </svg>
      </button>
    </header>

    <!-- Slide Menu -->
    <div v-if="menuOpen" class="fixed inset-0 z-40" @click="closeMenu"></div>
    <div v-if="menuOpen" class="absolute right-4 mt-14 w-52 bg-white rounded-xl shadow-lg ring-1 ring-black/5 z-50 overflow-hidden transition-all duration-150">
      <button @click="goToWearableSetup" class="block w-full text-left px-4 py-3 hover:bg-gray-50 text-sm font-medium text-gray-700">
        Camera Setup
      </button>
      <div class="border-t border-gray-100">
        <button @click="toggleDemoMode" class="flex items-center justify-between w-full px-4 py-3 hover:bg-gray-50 text-sm">
          <div class="flex items-center gap-2">
            <span>📹</span>
            <div>
              <p class="font-medium text-gray-700 text-left">Demo Mode</p>
              <p class="text-[10px] text-gray-400">Camera preview + boxes</p>
            </div>
          </div>
          <div :class="[
            'w-9 h-5 rounded-full transition-colors duration-200 relative',
            demoMode ? 'bg-amber-500' : 'bg-gray-300'
          ]">
            <div :class="[
              'absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform duration-200',
              demoMode ? 'translate-x-4' : 'translate-x-0.5'
            ]"></div>
          </div>
        </button>
      </div>
      <button @click="logout" class="block w-full text-left px-4 py-3 text-red-600 font-semibold hover:bg-red-50 text-sm border-t border-gray-100">Logout</button>
    </div>



    <!-- Main Content -->
    <div class="p-4 space-y-4">

      <!-- Greeting + Online Status -->
      <div class="bg-yellow-100 rounded-2xl shadow ring-1 ring-yellow-200/60 p-4 flex items-center gap-3">
        <div class="w-10 h-10 bg-yellow-400 rounded-full flex items-center justify-center font-bold text-gray-800 text-lg shrink-0">
          {{ (user?.name || 'U').charAt(0).toUpperCase() }}
        </div>
        <div>
          <h2 class="text-lg font-bold text-gray-800">Hello, {{ user?.name || 'User' }}</h2>
          <div class="flex items-center gap-1.5 mt-0.5">
            <span :class="['w-2 h-2 rounded-full', isNetworkOnline ? 'bg-green-500 animate-pulse' : 'bg-red-500']"></span>
            <p class="text-xs font-medium text-gray-600">
              {{ isNetworkOnline ? 'Online' : 'Offline (No Connection)' }}
            </p>
          </div>
        </div>
      </div>

      <!-- GPS Error -->
      <div v-if="error" class="bg-red-100 border-l-4 border-red-500 text-red-700 rounded-2xl p-4">
        <div class="flex items-start gap-2">
          <svg class="w-5 h-5 text-red-500 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
          </svg>
          <div class="flex-1">
            <p class="text-sm font-bold">Tracking Error</p>
            <p class="text-xs font-medium mt-1">{{ error }}</p>
            <p class="text-xs text-red-600 mt-1">Check your location permissions and GPS settings.</p>
            <button @click="retryTracking"
                    class="mt-2 px-3 py-1.5 bg-red-600 hover:bg-red-700 active:scale-95 text-white text-xs font-semibold rounded-lg transition-all flex items-center gap-1.5">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/>
              </svg>
              Retry GPS Tracking
            </button>
          </div>
        </div>
      </div>

      <!-- GPS Status -->
      <div class="bg-yellow-100 rounded-2xl shadow ring-1 ring-yellow-200/60 p-4">
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-sm font-bold tracking-tight text-gray-800">GPS Tracking</h2>
          <div :class="['w-3 h-3 rounded-full', isTracking ? 'bg-green-500 animate-pulse' : 'bg-red-500']"></div>
        </div>
        <div class="flex items-center">
          <div class="w-10 h-10 bg-gray-800 rounded-full flex items-center justify-center mr-3 shrink-0">
            <svg v-if="isTracking" class="w-5 h-5 text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"/>
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 11a3 3 0 11-6 0 3 3 0 016 0z"/>
            </svg>
            <svg v-else class="w-5 h-5 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636"/>
            </svg>
          </div>
          <div>
            <p class="text-sm font-semibold text-gray-800">{{ isTracking ? 'Tracking Active' : 'Tracking Inactive' }}</p>
            <p class="text-xs text-gray-500">{{ isTracking ? 'Your guardian can see your location' : 'Location sharing paused' }}</p>
          </div>
        </div>
        <div v-if="currentLocation" class="mt-3 pt-3 border-t border-yellow-200">
          <div class="flex flex-wrap gap-x-6 gap-y-2 text-xs">
            <div>
              <p class="text-[10px] font-medium tracking-wide uppercase text-gray-400">Coordinates</p>
              <p class="font-mono font-semibold text-gray-800">{{ currentLocation.latitude?.toFixed(6) }}, {{ currentLocation.longitude?.toFixed(6) }}</p>
            </div>
            <div>
              <p class="text-[10px] font-medium tracking-wide uppercase text-gray-400">Accuracy</p>
              <p class="font-semibold text-gray-800">&plusmn;{{ currentLocation.accuracy?.toFixed(0) }}m</p>
            </div>
            <div v-if="currentLocation.speed && currentLocation.speed > 0">
              <p class="text-[10px] font-medium tracking-wide uppercase text-gray-400">Speed</p>
              <p class="font-semibold text-gray-800">{{ (currentLocation.speed * 3.6).toFixed(1) }} km/h</p>
            </div>
          </div>
        </div>
      </div>

      <!-- Camera Status -->
      <div class="bg-white rounded-2xl shadow ring-1 ring-gray-100 p-4 space-y-3 transition-shadow hover:shadow-md">
        <div class="flex items-center justify-between">
          <h2 class="text-sm font-bold tracking-tight text-gray-800">Camera Status</h2>
          <span :class="[
            'text-xs font-bold px-2 py-1 rounded-full',
            activeCamera === 'day' ? 'bg-yellow-200 text-yellow-800' : 'bg-indigo-200 text-indigo-800'
          ]">
            {{ activeCamera === 'day' ? 'Day Mode' : 'Night Mode' }}
          </span>
        </div>

        <!-- Lux reading -->
        <div v-if="currentLux !== null" class="flex items-center gap-1.5 text-xs text-gray-500">
          <svg class="w-3.5 h-3.5 text-yellow-500" fill="currentColor" viewBox="0 0 20 20">
            <path d="M10 2a1 1 0 011 1v1a1 1 0 11-2 0V3a1 1 0 011-1zm4 8a4 4 0 11-8 0 4 4 0 018 0zm-.464 4.95l.707.707a1 1 0 001.414-1.414l-.707-.707a1 1 0 00-1.414 1.414zm2.12-10.607a1 1 0 010 1.414l-.706.707a1 1 0 11-1.414-1.414l.707-.707a1 1 0 011.414 0zM17 11a1 1 0 100-2h-1a1 1 0 100 2h1zm-7 4a1 1 0 011 1v1a1 1 0 11-2 0v-1a1 1 0 011-1zM5.05 6.464A1 1 0 106.465 5.05l-.708-.707a1 1 0 00-1.414 1.414l.707.707zm1.414 8.486l-.707.707a1 1 0 01-1.414-1.414l.707-.707a1 1 0 011.414 1.414zM4 11a1 1 0 100-2H3a1 1 0 000 2h1z"/>
          </svg>
          {{ currentLux }} lux
          <span v-if="averageLux !== null" class="text-gray-400">(avg: {{ averageLux }})</span>
          <span class="text-gray-400">(enter: &lt;{{ NIGHT_ENTER }} / exit: &gt;{{ NIGHT_EXIT }})</span>
        </div>

        <!-- Day camera -->
        <div class="flex items-center justify-between py-2 border-t border-gray-50">
          <div class="flex items-center gap-2">
            <div :class="['w-3 h-3 rounded-full shrink-0', dayCamConnected ? 'bg-green-500 animate-pulse' : 'bg-red-400']"></div>
            <div class="flex items-center gap-1.5">
              <svg class="w-4 h-4 text-yellow-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"/>
              </svg>
              <div>
                <p class="text-sm font-medium text-gray-800">Day Camera</p>
                <p class="text-xs text-gray-400 font-mono">{{ dayCamIp || 'No IP set' }}</p>
              </div>
            </div>
          </div>
          <span :class="['text-xs px-2 py-0.5 rounded-full font-medium', dayCamConnected ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-600']">
            {{ dayCamConnected ? 'Online' : dayCamStatus }}
          </span>
        </div>

        <div class="border-t border-gray-100"></div>

        <!-- Night camera -->
        <div class="flex items-center justify-between py-2">
          <div class="flex items-center gap-2">
            <div :class="['w-3 h-3 rounded-full shrink-0', nightCamConnected ? 'bg-green-500 animate-pulse' : 'bg-red-400']"></div>
            <div class="flex items-center gap-1.5">
              <svg class="w-4 h-4 text-indigo-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"/>
              </svg>
              <div>
                <p class="text-sm font-medium text-gray-800">Night Camera</p>
                <p class="text-xs text-gray-400 font-mono">{{ nightCamIp || 'No IP set' }}</p>
              </div>
            </div>
          </div>
          <span :class="['text-xs px-2 py-0.5 rounded-full font-medium', nightCamConnected ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-600']">
            {{ nightCamConnected ? 'Online' : nightCamStatus }}
          </span>
        </div>


      </div>

      <!-- NOTE: Camera Preview block removed.
           Base64 frame encoding is permanently disabled. Use Android Studio
           Logcat (filter: ObjectDetection) to debug detection output. -->

      <!-- Detection (Alert Banner) -->
      <div class="bg-white rounded-2xl shadow ring-1 ring-gray-100 p-4 transition-shadow hover:shadow-md">
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-sm font-bold tracking-tight text-gray-800">Nearest Obstacle</h2>
          <span class="text-[10px] font-medium tracking-wide uppercase text-gray-400">via {{ nearestObject?.camera || activeCamera }} cam</span>
        </div>

        <div v-if="nearestObject" :class="[
          'border-l-4 rounded-r-xl p-3',
          nearestObject.distance === 'imminent'
            ? 'border-red-500 bg-red-50 animate-pulse'
            : 'border-orange-400 bg-orange-50'
        ]">
          <p class="text-2xl font-black text-gray-800 capitalize">{{ nearestObject.class }}</p>
          <div class="flex flex-wrap gap-2 mt-2">
            <span :class="[
              'inline-flex items-center gap-1 text-xs font-semibold px-2 py-0.5 rounded-full',
              nearestObject.distance === 'imminent'
                ? 'bg-red-200 text-red-800'
                : 'bg-orange-200 text-orange-800'
            ]">
              <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"/></svg>
              {{ nearestObject.distance === 'imminent' ? '⚠ IMMINENT' : nearestObject.distance }}
            </span>
            <span class="inline-flex items-center gap-1 text-xs font-semibold px-2 py-0.5 rounded-full bg-blue-100 text-blue-800">
              <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"/></svg>
              {{ nearestObject.direction }}
            </span>
            <span class="text-xs font-medium px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">
              {{ (nearestObject.confidence * 100).toFixed(0) }}% conf
            </span>
          </div>
        </div>

        <div v-else class="flex items-center justify-center gap-2 py-5 text-green-600">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/>
          </svg>
          <p class="text-sm font-semibold">Clear ahead</p>
        </div>
      </div>

      <!-- Demo Mode Preview — only renders when demoMode is active -->
      <div v-if="demoMode" class="bg-gray-900 rounded-2xl shadow ring-1 ring-gray-700 p-3 transition-shadow hover:shadow-md">
        <div class="flex items-center justify-between mb-2">
          <div class="flex items-center gap-2">
            <span class="w-2 h-2 bg-red-500 rounded-full animate-pulse"></span>
            <h2 class="text-sm font-bold tracking-tight text-white">Camera Preview</h2>
          </div>
          <span class="text-[9px] font-bold tracking-wider uppercase px-1.5 py-0.5 rounded bg-amber-500 text-white">DEMO</span>
        </div>
        <div class="relative w-full overflow-hidden rounded-lg bg-black" style="aspect-ratio: 4/3;">
          <canvas ref="previewCanvas" width="320" height="240"
            class="w-full h-full object-contain" style="image-rendering: auto;"></canvas>
        </div>
        <p class="text-[10px] text-amber-400 mt-2 text-center font-medium">
          ⚠ Demo mode active
        </p>
      </div>

      <!-- Performance Metrics (lightweight text-only, always visible) -->
      <div class="bg-white rounded-2xl shadow ring-1 ring-gray-100 p-4">
        <h2 class="text-sm font-bold tracking-tight text-gray-800 mb-3">Detection Performance</h2>
        <div class="grid grid-cols-3 gap-3 text-center">
          <div class="bg-gray-50 rounded-xl p-2">
            <p class="text-[10px] font-medium tracking-wide uppercase text-gray-400 mb-1">Calls</p>
            <p class="text-lg font-black text-gray-800">{{ perfMetrics.detectCalls }}</p>
          </div>
          <div class="bg-gray-50 rounded-xl p-2">
            <p class="text-[10px] font-medium tracking-wide uppercase text-gray-400 mb-1">Inference</p>
            <p class="text-lg font-black text-gray-800">{{ perfMetrics.avgInferenceMs }}<span class="text-xs font-medium text-gray-500">ms</span></p>
          </div>
          <div class="bg-gray-50 rounded-xl p-2">
            <p class="text-[10px] font-medium tracking-wide uppercase text-gray-400 mb-1">Total</p>
            <p class="text-lg font-black text-gray-800">{{ perfMetrics.avgDetectMs }}<span class="text-xs font-medium text-gray-500">ms</span></p>
          </div>
        </div>
      </div>

      <!-- Guardian Info -->
      <div class="bg-white rounded-2xl shadow ring-1 ring-gray-100 p-4 transition-shadow hover:shadow-md">
        <h2 class="text-sm font-bold tracking-tight text-gray-800 mb-3 flex items-center gap-2">
          <svg class="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                  d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"/>
          </svg>
          Guardian Information
        </h2>
        <div v-if="guardian">
          <div class="flex items-center gap-3 mb-3">
            <div class="w-10 h-10 bg-blue-100 rounded-full flex items-center justify-center font-bold text-blue-700 text-lg shrink-0">
              {{ (guardian.name || 'G').charAt(0).toUpperCase() }}
            </div>
            <div>
              <p class="text-sm font-semibold text-gray-800">{{ guardian.name }}</p>
              <p class="text-xs text-gray-500 break-all">{{ guardian.email }}</p>
            </div>
          </div>
          <div class="pt-3 border-t border-gray-100">
            <div class="flex items-center justify-center gap-2">
              <span class="w-2 h-2 bg-green-500 rounded-full animate-pulse"></span>
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
      <div class="bg-blue-50 border-l-4 border-blue-400 rounded-r-2xl p-4">
        <div class="flex items-start gap-2">
          <svg class="w-5 h-5 text-blue-600 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                  d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/>
          </svg>
          <div>
            <p class="text-xs font-semibold text-blue-800">Background Tracking Active</p>
            <p class="text-xs text-blue-700 mt-0.5">Your location is tracked even when this app is closed or your phone is locked.</p>
          </div>
        </div>
      </div>

      <!-- Distress: Sending -->
      <div v-if="isSendingDistress" class="fixed top-20 left-4 right-4 bg-amber-100 border-l-4 border-amber-500 text-amber-800 p-4 rounded-r-xl shadow-xl z-50 animate-fadeIn">
        <div class="flex items-start gap-3">
          <div class="w-5 h-5 border-2 border-amber-500 border-t-transparent rounded-full animate-spin shrink-0 mt-0.5"></div>
          <div>
            <p class="font-bold text-sm">Sending Distress Signal…</p>
            <p class="text-xs mt-0.5">Please wait while we notify your guardian.</p>
          </div>
        </div>
      </div>

      <!-- Distress: Sent -->
      <div v-if="distressSent && !isSendingDistress" class="fixed top-20 left-4 right-4 bg-green-100 border-l-4 border-green-600 text-green-800 p-4 rounded-r-xl shadow-xl z-50 animate-fadeIn">
        <div class="flex items-start gap-3">
          <svg class="w-5 h-5 text-green-600 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/>
          </svg>
          <div>
            <p class="font-bold text-sm">Distress Signal Sent!</p>
            <p class="text-xs mt-0.5">Your guardian has been notified of your location.</p>
          </div>
        </div>
      </div>

      <!-- Distress: Failed -->
      <div v-if="distressFailed && !isSendingDistress" class="fixed top-20 left-4 right-4 bg-red-100 border-l-4 border-red-600 text-red-800 p-4 rounded-r-xl shadow-xl z-50 animate-fadeIn">
        <div class="flex items-start gap-3">
          <svg class="w-5 h-5 text-red-600 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
          </svg>
          <div>
            <p class="font-bold text-sm">Failed to Send Signal</p>
            <p class="text-xs mt-0.5">{{ distressErrorMessage }}</p>
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