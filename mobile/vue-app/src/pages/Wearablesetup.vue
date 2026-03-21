<script setup>
/**
 * WearableSetup.vue
 * Import this as: import WearableProvisioningService from '../services/WearableProvisioningService'
 * Make sure the .ts filename on disk matches that casing exactly.
 */

import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { App } from '@capacitor/app';
import WearableProvisioningService from '../services/WearableProvisioningservice';

const router = useRouter();

// Handle hardware back button
let backButtonListener = null;


onMounted(() => {
  backButtonListener = App.addListener('backButton', () => {
    router.push('/pwd-dashboard');
  });
});

onUnmounted(() => {
  if (backButtonListener) {
    backButtonListener.remove();
  }
});

// ── State ─────────────────────────────────────────────────────────────────────

const ssid        = ref('');
const password    = ref('');
const showPwd     = ref(false);
const isRunning   = ref(false);
const isDone      = ref(false);
const countdown   = ref(0);   // used during post-reset wait

const statusLog   = ref([]);
const dayBoard    = ref(null);
const nightBoard  = ref(null);

const canStart = computed(() =>
    ssid.value.trim().length > 0 && !isRunning.value
);

const STATUS_TEXT = {
  scanning_ble:       { icon: '🔵', text: 'Scanning for wearable cameras via Bluetooth...' },
  found_day:          { icon: '☀️', text: 'Day camera found!' },
  found_night:        { icon: '🌙', text: 'Night camera found!' },
  provisioning_day:   { icon: '📡', text: 'Sending Wi-Fi credentials to day camera...' },
  provisioning_night: { icon: '📡', text: 'Sending Wi-Fi credentials to night camera...' },
  waiting_wifi:       { icon: '⏳', text: 'Waiting for cameras to connect to your hotspot...' },
  scanning_subnet:    { icon: '🔍', text: 'Finding camera IPs on the network...' },
  resetting:          { icon: '🔄', text: 'Sending reset command...' },
  done:               { icon: '✅', text: 'Done!' },
  error:              { icon: '❌', text: 'An error occurred' },
};

// ── Helpers ───────────────────────────────────────────────────────────────────

const pushLog = (state) => {
  const entry = STATUS_TEXT[state.status] || { icon: 'ℹ️', text: state.message };
  statusLog.value.push({
    icon: entry.icon,
    text: state.message || entry.text,
    type: state.status === 'error' ? 'error' : 'info'
  });
  if (state.dayBoard)   dayBoard.value   = state.dayBoard;
  if (state.nightBoard) nightBoard.value = state.nightBoard;
  if (state.status === 'done')  isDone.value    = true;
  if (state.status === 'error') isRunning.value = false;
};

// Counts down on screen while waiting for boards to reboot after reset
const startCountdown = async (seconds) => {
  countdown.value = seconds;
  while (countdown.value > 0) {
    await new Promise(r => setTimeout(r, 1000));
    countdown.value--;
  }
};

// ── Actions ───────────────────────────────────────────────────────────────────

const startSetup = async () => {
  isRunning.value  = true;
  isDone.value     = false;
  statusLog.value  = [];
  dayBoard.value   = null;
  nightBoard.value = null;

  try {
    await WearableProvisioningService.provision(ssid.value.trim(), password.value, pushLog);
  } catch (err) {
    statusLog.value.push({ icon: '❌', text: err.message, type: 'error' });
  } finally {
    isRunning.value = false;
  }
};

const rediscover = async () => {
  isRunning.value  = true;
  isDone.value     = false;
  statusLog.value  = [];
  dayBoard.value   = null;
  nightBoard.value = null;

  try {
    await WearableProvisioningService.rediscoverBoards(pushLog);
  } catch (err) {
    statusLog.value.push({ icon: '❌', text: err.message, type: 'error' });
  } finally {
    isRunning.value = false;
  }
};

/**
 * Method 3 — Remote reset flow:
 *   1. App hits GET /reset on each board's saved IP over Wi-Fi
 *   2. Board wipes NVS creds → reboots into BLE advertising mode
 *   3. App waits 10s (with visible countdown) for reboot to complete
 *   4. Guardian can then tap "Start Setup" to re-provision
 */
const resetAndReprovision = async () => {
  isRunning.value  = true;
  isDone.value     = false;
  statusLog.value  = [];
  dayBoard.value   = null;
  nightBoard.value = null;

  try {
    await WearableProvisioningService.resetBothBoards(pushLog);

    // Show countdown while boards reboot into BLE mode
    statusLog.value.push({
      icon: '⏳',
      text: 'Boards are rebooting — please wait...',
      type: 'info'
    });
    await startCountdown(10);

    statusLog.value.push({
      icon: '✅',
      text: 'Ready! Enter your hotspot details above and tap Start Setup.',
      type: 'info'
    });
    isDone.value = false; // reset done so the setup form is re-enabled
  } catch (err) {
    statusLog.value.push({ icon: '❌', text: err.message, type: 'error' });
  } finally {
    isRunning.value = false;
    countdown.value = 0;
  }
};
</script>

<template>
  <div class="min-h-screen bg-gray-100 flex flex-col">

    <!-- Header -->
    <header class="bg-[#f7d686] shadow-md px-4 py-3 flex items-center gap-3">
      <button @click="router.push('/pwd-dashboard')" class="text-gray-700 text-xl">
        Back
      </button>
      <div>
        <h1 class="text-lg font-bold text-gray-800">Wearable Setup</h1>
        <p class="text-xs text-gray-600 mt-0.5">Connect cameras to your phone's hotspot</p>
      </div>
    </header>

    <div class="p-4 space-y-4">

      <!-- Before-you-start instructions -->
      <div class="bg-blue-50 border border-blue-200 rounded-2xl p-4">
        <p class="text-sm font-semibold text-blue-800 mb-1">Before you start:</p>
        <ol class="text-xs text-blue-700 space-y-1 list-decimal list-inside">
          <li>Enable your phone's <strong>Mobile Hotspot</strong> in Settings</li>
          <li>Make sure Bluetooth is turned on</li>
          <li>Power on the wearable device</li>
          <li>Enter your hotspot details below and tap Setup</li>
        </ol>
      </div>

      <!-- Credentials form -->
      <div class="bg-white rounded-2xl shadow p-4 space-y-3">
        <h2 class="font-semibold text-gray-800">Your Hotspot Credentials</h2>

        <div>
          <label class="block text-xs font-semibold text-gray-600 mb-1">Hotspot Name (SSID)</label>
          <input
              v-model="ssid"
              type="text"
              placeholder="e.g. My Phone"
              :disabled="isRunning"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-yellow-400 disabled:bg-gray-50"
          />
        </div>

        <div>
          <label class="block text-xs font-semibold text-gray-600 mb-1">Hotspot Password</label>
          <div class="relative">
            <input
                v-model="password"
                :type="showPwd ? 'text' : 'password'"
                placeholder="Hotspot password"
                :disabled="isRunning"
                class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-yellow-400 disabled:bg-gray-50 pr-12"
            />
            <button
                @click="showPwd = !showPwd"
                class="absolute right-3 top-2 text-gray-400 text-xs"
            >
              {{ showPwd ? 'Hide' : 'Show' }}
            </button>
          </div>
        </div>

        <p class="text-xs text-gray-400">
          📌 Device PIN: <strong class="text-gray-600">walksense1</strong>
          (printed on the wearable label)
        </p>
      </div>

      <!-- Primary action buttons -->
      <div class="space-y-2">
        <button
            @click="startSetup"
            :disabled="!canStart"
            :class="[
            'w-full py-3 rounded-xl font-bold text-sm transition',
            canStart
              ? 'bg-yellow-400 hover:bg-yellow-500 text-gray-900'
              : 'bg-gray-200 text-gray-400 cursor-not-allowed'
          ]"
        >
          <span v-if="isRunning && !countdown" class="flex items-center justify-center gap-2">
            <svg class="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z"/>
            </svg>
            Setting up...
          </span>
          <span v-else-if="countdown > 0">
            ⏳ Waiting for reboot... {{ countdown }}s
          </span>
          <span v-else>🔧 Start Wearable Setup</span>
        </button>

        <button
            v-if="!isRunning"
            @click="rediscover"
            class="w-full py-2.5 rounded-xl text-sm text-gray-600 border border-gray-300 hover:bg-gray-50"
        >
          🔍 Just find cameras (already provisioned)
        </button>
      </div>

      <!-- Live status log -->
      <div v-if="statusLog.length > 0" class="bg-white rounded-2xl shadow p-4">
        <h2 class="font-semibold text-gray-800 mb-3">Setup Progress</h2>
        <div class="space-y-2 max-h-48 overflow-y-auto">
          <div
              v-for="(entry, i) in statusLog"
              :key="i"
              :class="[
              'flex items-start gap-2 text-sm',
              entry.type === 'error' ? 'text-red-600' : 'text-gray-700'
            ]"
          >
            <span class="flex-shrink-0">{{ entry.icon }}</span>
            <span>{{ entry.text }}</span>
          </div>
        </div>
      </div>

      <!-- Success card -->
      <div v-if="isDone" class="bg-green-50 border border-green-200 rounded-2xl p-4 space-y-3">
        <p class="font-bold text-green-800 text-center">✅ Cameras Connected!</p>

        <div v-if="dayBoard" class="flex items-center justify-between bg-white rounded-xl p-3">
          <div class="flex items-center gap-2">
            <span class="text-lg">☀️</span>
            <div>
              <p class="text-sm font-semibold text-gray-800">Day Camera</p>
              <p class="text-xs text-gray-500 font-mono">{{ dayBoard.ip }}</p>
            </div>
          </div>
          <span class="text-xs bg-green-100 text-green-700 px-2 py-0.5 rounded-full font-medium">Online</span>
        </div>
        <div v-else class="flex items-center gap-2 text-sm text-red-600 bg-white rounded-xl p-3">
          <span>☀️</span>
          <p>Day camera not found — check it is powered on</p>
        </div>

        <div v-if="nightBoard" class="flex items-center justify-between bg-white rounded-xl p-3">
          <div class="flex items-center gap-2">
            <span class="text-lg">🌙</span>
            <div>
              <p class="text-sm font-semibold text-gray-800">Night Camera</p>
              <p class="text-xs text-gray-500 font-mono">{{ nightBoard.ip }}</p>
            </div>
          </div>
          <span class="text-xs bg-green-100 text-green-700 px-2 py-0.5 rounded-full font-medium">Online</span>
        </div>
        <div v-else class="flex items-center gap-2 text-sm text-red-600 bg-white rounded-xl p-3">
          <span>🌙</span>
          <p>Night camera not found — check it is powered on</p>
        </div>

        <p class="text-xs text-green-700 text-center">You can now go back to the dashboard.</p>
      </div>

      <!-- ── Method 3: Reset & Re-provision ── -->
      <div class="bg-red-50 border border-red-200 rounded-2xl p-4 space-y-2">
        <p class="text-xs font-semibold text-red-800">🔄 Reset Cameras (Demo / Debug)</p>
        <p class="text-xs text-red-700">
          Use this to wipe saved Wi-Fi credentials from both boards and
          start provisioning from scratch. Boards must currently be
          connected to your hotspot for this to work.
        </p>
        <button
            @click="resetAndReprovision"
            :disabled="isRunning"
            :class="[
            'w-full py-2.5 rounded-xl text-sm font-semibold transition',
            isRunning
              ? 'bg-gray-200 text-gray-400 cursor-not-allowed'
              : 'bg-red-100 hover:bg-red-200 text-red-700 border border-red-300'
          ]"
        >
          <span v-if="isRunning && countdown > 0">
            ⏳ Rebooting boards... {{ countdown }}s
          </span>
          <span v-else-if="isRunning">
            🔄 Resetting...
          </span>
          <span v-else>
            🔄 Reset & Re-provision Both Cameras
          </span>
        </button>
      </div>

      <!-- Physical reset fallback tip -->
      <div class="bg-amber-50 border border-amber-200 rounded-2xl p-4">
        <p class="text-xs font-semibold text-amber-800 mb-1">⚠️ Camera not connecting?</p>
        <p class="text-xs text-amber-700">
          If cameras are not on the hotspot yet, hold the wearable's side button for
          <strong>5 seconds</strong> — this physically resets the saved Wi-Fi credentials
          and puts both boards back into Bluetooth setup mode.
          Then tap <strong>Start Wearable Setup</strong> above.
        </p>
      </div>

    </div>
  </div>
</template>