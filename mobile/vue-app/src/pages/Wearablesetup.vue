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

const currentStatus = ref(null);
const dayBoard    = ref(null);
const nightBoard  = ref(null);

const canStart = computed(() =>
    ssid.value.trim().length > 0 && !isRunning.value
);

const STATUS_TEXT = {
  scanning_ble:       { id: 'scanning', text: 'Scanning for wearable cameras via Bluetooth...' },
  found_day:          { id: 'found', text: 'Day camera found!' },
  found_night:        { id: 'found', text: 'Night camera found!' },
  provisioning_day:   { id: 'provisioning', text: 'Sending Wi-Fi credentials to day camera...' },
  provisioning_night: { id: 'provisioning', text: 'Sending Wi-Fi credentials to night camera...' },
  waiting_wifi:       { id: 'waiting', text: 'Waiting for cameras to connect to your hotspot...' },
  scanning_subnet:    { id: 'scanning', text: 'Finding camera IPs on the network...' },
  resetting:          { id: 'resetting', text: 'Sending reset command...' },
  done:               { id: 'done', text: 'Done!' },
  error:              { id: 'error', text: 'An error occurred' },
};

// ── Helpers ───────────────────────────────────────────────────────────────────

const pushLog = (state) => {
  const entry = STATUS_TEXT[state.status] || { id: 'info', text: state.message };
  
  currentStatus.value = {
    id: entry.id,
    text: state.message || entry.text,
    type: state.status === 'error' ? 'error' : 'info'
  };

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
  currentStatus.value = null;
  dayBoard.value   = null;
  nightBoard.value = null;

  try {
    await WearableProvisioningService.provision(ssid.value.trim(), password.value, pushLog);
  } catch (err) {
    currentStatus.value = { id: 'error', text: err.message, type: 'error' };
  } finally {
    isRunning.value = false;
  }
};

const rediscover = async () => {
  isRunning.value  = true;
  isDone.value     = false;
  currentStatus.value = null;
  dayBoard.value   = null;
  nightBoard.value = null;

  try {
    await WearableProvisioningService.rediscoverBoards(pushLog);
  } catch (err) {
    currentStatus.value = { id: 'error', text: err.message, type: 'error' };
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
  currentStatus.value = null;
  dayBoard.value   = null;
  nightBoard.value = null;

  try {
    await WearableProvisioningService.resetBothBoards(pushLog);

    // Show countdown while boards reboot into BLE mode
    currentStatus.value = {
      id: 'waiting',
      text: 'Boards are rebooting — please wait...',
      type: 'info'
    };
    await startCountdown(10);

    currentStatus.value = {
      id: 'done',
      text: 'Ready! Enter your hotspot details above and tap Start Setup.',
      type: 'info'
    };
    isDone.value = false; // reset done so the setup form is re-enabled
  } catch (err) {
    currentStatus.value = { id: 'error', text: err.message, type: 'error' };
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
      <button @click="router.push('/pwd-dashboard')" class="flex items-center justify-center p-2 -ml-2 text-gray-700 hover:bg-black/5 rounded-full transition" aria-label="Go back">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M15 19l-7-7 7-7" />
        </svg>
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

        <p class="text-xs text-gray-500 flex items-center gap-1.5 mt-2">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
          <span>Device PIN: <strong class="text-gray-700">walksense1</strong> (printed on the wearable label)</span>
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
            Waiting for reboot... {{ countdown }}s
          </span>
          <span v-else>Start Wearable Setup</span>
        </button>

        <button
            v-if="!isRunning"
            @click="rediscover"
            class="w-full py-2.5 rounded-xl text-sm text-gray-600 border border-gray-300 hover:bg-gray-50"
        >
          Find cameras (already provisioned)
        </button>
      </div>

      <!-- Current Status Card -->
      <div v-if="currentStatus && !isDone" class="bg-white rounded-2xl shadow p-5 flex flex-col items-center justify-center text-center space-y-3">
        <div v-if="currentStatus.type === 'error'" class="w-12 h-12 bg-red-100 text-red-600 rounded-full flex items-center justify-center">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" /></svg>
        </div>
        <div v-else class="w-10 h-10 border-4 border-yellow-200 border-t-yellow-500 rounded-full animate-spin"></div>
        
        <div>
          <h3 class="font-semibold text-gray-800">{{ currentStatus.type === 'error' ? 'Setup Failed' : 'Setting up...' }}</h3>
          <p :class="['text-sm mt-1', currentStatus.type === 'error' ? 'text-red-500' : 'text-gray-500']">{{ currentStatus.text }}</p>
        </div>
      </div>

      <!-- Success card -->
      <div v-if="isDone" :class="[
        'rounded-2xl p-4 space-y-3 border',
        dayBoard || nightBoard ? 'bg-green-50 border-green-200' : 'bg-amber-50 border-amber-200'
      ]">
        <div v-if="dayBoard || nightBoard" class="flex items-center justify-center gap-2 font-bold mb-2"
             :class="dayBoard && nightBoard ? 'text-green-800' : 'text-amber-800'">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" :class="dayBoard && nightBoard ? 'text-green-600' : 'text-amber-600'" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" /></svg>
          <p>{{ dayBoard && nightBoard ? 'Cameras Connected!' : 'Partial Connection' }}</p>
        </div>
        <div v-else class="flex items-center justify-center gap-2 text-amber-800 font-bold mb-2">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-amber-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" /></svg>
          <p>No Cameras Found</p>
        </div>

        <div v-if="dayBoard" class="flex items-center justify-between bg-white rounded-xl p-3 shadow-sm border border-gray-100">
          <div class="flex items-center gap-3">
            <div class="w-8 h-8 rounded-full bg-blue-50 text-blue-500 flex items-center justify-center">
              <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5"><path stroke-linecap="round" stroke-linejoin="round" d="M12 3v2.25m6.364.386l-1.591 1.591M21 12h-2.25m-.386 6.364l-1.591-1.591M12 18.75V21m-4.773-4.227l-1.591 1.591M5.25 12H3m4.227-4.773L5.636 5.636M15.75 12a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0z" /></svg>
            </div>
            <div>
              <p class="text-sm font-semibold text-gray-800">Day Camera</p>
              <p class="text-xs text-gray-500 font-mono">{{ dayBoard.ip }}</p>
            </div>
          </div>
          <span class="text-[10px] uppercase tracking-wider bg-green-100 text-green-700 px-2.5 py-1 rounded-full font-bold">Online</span>
        </div>
        <div v-else class="flex items-center gap-3 bg-white rounded-xl p-3 shadow-sm border border-red-100">
          <div class="w-8 h-8 rounded-full bg-red-50 text-red-500 flex items-center justify-center">
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5"><path stroke-linecap="round" stroke-linejoin="round" d="M12 3v2.25m6.364.386l-1.591 1.591M21 12h-2.25m-.386 6.364l-1.591-1.591M12 18.75V21m-4.773-4.227l-1.591 1.591M5.25 12H3m4.227-4.773L5.636 5.636M15.75 12a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0z" /></svg>
          </div>
          <p class="text-sm text-red-600 font-medium">Day camera not found</p>
        </div>

        <div v-if="nightBoard" class="flex items-center justify-between bg-white rounded-xl p-3 shadow-sm border border-gray-100">
          <div class="flex items-center gap-3">
            <div class="w-8 h-8 rounded-full bg-indigo-50 text-indigo-500 flex items-center justify-center">
              <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5"><path stroke-linecap="round" stroke-linejoin="round" d="M21.752 15.002A9.718 9.718 0 0118 15.75c-5.385 0-9.75-4.365-9.75-9.75 0-1.33.266-2.597.748-3.752A9.753 9.753 0 003 11.25C3 16.635 7.365 21 12.75 21a9.753 9.753 0 009.002-5.998z" /></svg>
            </div>
            <div>
              <p class="text-sm font-semibold text-gray-800">Night Camera</p>
              <p class="text-xs text-gray-500 font-mono">{{ nightBoard.ip }}</p>
            </div>
          </div>
          <span class="text-[10px] uppercase tracking-wider bg-green-100 text-green-700 px-2.5 py-1 rounded-full font-bold">Online</span>
        </div>
        <div v-else class="flex items-center gap-3 bg-white rounded-xl p-3 shadow-sm border border-red-100">
          <div class="w-8 h-8 rounded-full bg-red-50 text-red-500 flex items-center justify-center">
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5"><path stroke-linecap="round" stroke-linejoin="round" d="M21.752 15.002A9.718 9.718 0 0118 15.75c-5.385 0-9.75-4.365-9.75-9.75 0-1.33.266-2.597.748-3.752A9.753 9.753 0 003 11.25C3 16.635 7.365 21 12.75 21a9.753 9.753 0 009.002-5.998z" /></svg>
          </div>
          <p class="text-sm text-red-600 font-medium">Night camera not found</p>
        </div>

        <p v-if="dayBoard || nightBoard" class="text-xs text-green-700 text-center font-medium mt-2">You can now go back to the dashboard.</p>
        <p v-else class="text-xs text-amber-700 text-center font-medium mt-2">Make sure cameras are powered on and try again.</p>
      </div>

      <!-- ── Method 3: Reset & Re-provision ── -->
      <div class="bg-red-50 border border-red-200 rounded-2xl p-4 space-y-3">
        <div class="flex items-center gap-2 text-red-800">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg>
          <p class="text-sm font-bold">Reset Cameras (Demo / Debug)</p>
        </div>
        <p class="text-xs text-red-700 leading-relaxed">
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
              : 'bg-white hover:bg-red-50 text-red-700 border border-red-300'
          ]"
        >
          <span v-if="isRunning && countdown > 0">
            Waiting for reboot... {{ countdown }}s
          </span>
          <span v-else-if="isRunning">
            Resetting...
          </span>
          <span v-else>
            Reset & Re-provision Both Cameras
          </span>
        </button>
      </div>

      <!-- Physical reset fallback tip -->
      <div class="bg-amber-50 border border-amber-200 rounded-2xl p-4 flex gap-3">
        <div class="flex-shrink-0 mt-0.5 text-amber-500">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" /></svg>
        </div>
        <div>
          <p class="text-sm font-bold text-amber-800 mb-1">Camera not connecting?</p>
          <p class="text-xs text-amber-700 leading-relaxed">
            If cameras are not on the hotspot yet, hold the wearable's side button for
            <strong>5 seconds</strong> — this physically resets the saved Wi-Fi credentials
            and puts both boards back into Bluetooth setup mode.
            Then tap <strong>Start Wearable Setup</strong> above.
          </p>
        </div>
      </div>

    </div>
  </div>
</template>