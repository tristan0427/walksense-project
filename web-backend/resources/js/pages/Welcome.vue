<script setup lang="ts">
import { Head } from '@inertiajs/vue3'
import { ref } from 'vue'

const currentYear = new Date().getFullYear()
const mobileMenuOpen = ref(false)

const features = [
  {
    title: 'Real-Time GPS Tracking',
    description: 'Guardian monitors PWD location on a live map with battery status and accuracy indicators. Location updates every 20 seconds.',
    icon: 'gps',
  },
  {
    title: 'AI Object Detection',
    description: 'Dual ESP32-CAM wearable detects obstacles using YOLO and announces them via text-to-speech for safe navigation.',
    icon: 'detection',
  },
  {
    title: 'Emergency Distress Signal',
    description: 'Physical push button on the wearable sends an immediate emergency alert with GPS coordinates to the guardian.',
    icon: 'emergency',
  },
  {
    title: 'Background Tracking',
    description: 'Continuous location sharing even when the app is minimized, ensuring the guardian always knows where the PWD is.',
    icon: 'background',
  },
]

const steps = [
  {
    number: '01',
    title: 'Download & Register',
    description: 'Guardian downloads the app and creates an account for themselves and the PWD they care for.',
  },
  {
    number: '02',
    title: 'Connect the Wearable',
    description: 'Pair the ESP32-CAM wearable device via WiFi. The device handles object detection and distress signals.',
  },
  {
    number: '03',
    title: 'Start Walking Safely',
    description: 'PWD receives real-time voice alerts for nearby obstacles. Guardian monitors live location on the map.',
  },
  {
    number: '04',
    title: 'Emergency Ready',
    description: 'Press the distress button anytime for an immediate notification to the guardian with exact location.',
  },
]

const scrollTo = (id: string) => {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' })
  mobileMenuOpen.value = false
}
</script>

<template>
  <Head>
      <link rel="icon" href="/logo.png" type="image/png" />
      <link rel="preconnect" href="https://fonts.googleapis.com" />
      <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin="anonymous" />
      <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap" rel="stylesheet" />
      <meta name="description" content="WalkSense helps visually impaired individuals navigate safely with real-time object detection and GPS tracking — giving guardians peace of mind." />
  </Head>

  <div class="font-[Inter] text-gray-900 overflow-x-hidden">

    <!-- Navbar -->
    <nav class="fixed top-0 left-0 right-0 z-50 bg-[#f7d686]/92 backdrop-blur-md border-b border-black/5">
      <div class="max-w-6xl mx-auto px-6 flex items-center justify-between h-16">
        <div class="flex items-center gap-2.5 cursor-pointer" @click="scrollTo('hero')">
          <img src="/logo.png" alt="WalkSense" class="w-9 h-9 rounded-full" />
          <span class="font-bold text-lg tracking-tight">WalkSense</span>
        </div>
        <!-- Desktop Nav -->
        <div class="hidden md:flex items-center gap-8">
          <a @click="scrollTo('features')" class="text-sm font-medium cursor-pointer hover:opacity-70 transition-opacity">Features</a>
          <a @click="scrollTo('how-it-works')" class="text-sm font-medium cursor-pointer hover:opacity-70 transition-opacity">How It Works</a>
          <a @click="scrollTo('download')" class="bg-gray-900 text-white text-sm font-semibold px-5 py-2 rounded-full hover:bg-gray-700 transition-colors cursor-pointer">Download App</a>
        </div>
        <!-- Mobile Toggle -->
        <button class="md:hidden p-1" @click="mobileMenuOpen = !mobileMenuOpen">
          <svg v-if="!mobileMenuOpen" width="24" height="24" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16"/>
          </svg>
          <svg v-else width="24" height="24" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
          </svg>
        </button>
      </div>
      <!-- Mobile Menu -->
      <div v-if="mobileMenuOpen" class="md:hidden bg-[#f7d686]/98 px-6 py-5 flex flex-col gap-5 border-b border-black/5">
        <a @click="scrollTo('features')" class="text-sm font-medium cursor-pointer">Features</a>
        <a @click="scrollTo('how-it-works')" class="text-sm font-medium cursor-pointer">How It Works</a>
        <a @click="scrollTo('download')" class="bg-gray-900 text-white text-sm font-semibold px-5 py-2 rounded-full text-center cursor-pointer">Download App</a>
      </div>
    </nav>

    <!-- Hero -->
    <section id="hero" class="pt-36 pb-20 min-h-[80vh] flex items-center bg-gradient-to-br from-[#f7d686] via-[#f5c84e] to-[#f7d686]">
      <div class="max-w-6xl mx-auto px-6 flex flex-col-reverse md:flex-row items-center gap-10 md:gap-16">
        <div class="flex-1 text-center md:text-left">
          <h1 class="text-3xl md:text-5xl font-extrabold leading-tight tracking-tight mb-6">
            Empowering Independence,<br/>
            <span>Ensuring Safety.</span>
          </h1>
          <p class="text-lg text-gray-700 leading-relaxed mb-9 max-w-xl mx-auto md:mx-0">
            WalkSense helps visually impaired individuals navigate safely with
            real-time object detection and GPS tracking — giving guardians peace of mind.
          </p>
          <div class="flex gap-4 flex-wrap justify-center md:justify-start">
            <a @click="scrollTo('download')" class="bg-gray-900 text-white font-semibold px-8 py-3.5 rounded-full text-base hover:bg-gray-700 transition-colors cursor-pointer">
              Download WalkSense
            </a>
            <a @click="scrollTo('features')" class="border-2 border-gray-900 text-gray-900 font-semibold px-8 py-3.5 rounded-full text-base hover:bg-black/5 transition-colors cursor-pointer">
              Learn More
            </a>
          </div>
        </div>
        <div class="shrink-0">
          <div class="w-44 h-44 md:w-72 md:h-72 bg-gray-900 rounded-full flex items-center justify-center shadow-2xl">
            <img src="/logo.png" alt="WalkSense Logo" class="w-40 h-40 md:w-64 md:h-64" />
          </div>
        </div>
      </div>
    </section>

    <!-- Features -->
    <section id="features" class="py-24 bg-white">
      <div class="max-w-6xl mx-auto px-6">
        <div class="text-center mb-14">
          <h2 class="text-3xl md:text-4xl font-extrabold tracking-tight mb-3">Key Features</h2>
          <p class="text-gray-500">Everything needed for safe, independent mobility.</p>
        </div>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div
            v-for="feature in features"
            :key="feature.title"
            class="bg-gray-50 border border-gray-200 rounded-2xl p-8 hover:-translate-y-1 hover:shadow-lg transition-all duration-200"
          >
            <div class="w-14 h-14 bg-[#f7d686] rounded-xl flex items-center justify-center mb-5">
              <svg v-if="feature.icon === 'gps'" width="28" height="28" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"/>
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 11a3 3 0 11-6 0 3 3 0 016 0z"/>
              </svg>
              <svg v-if="feature.icon === 'detection'" width="28" height="28" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
              </svg>
              <svg v-if="feature.icon === 'emergency'" width="28" height="28" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"/>
              </svg>
              <svg v-if="feature.icon === 'background'" width="28" height="28" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"/>
              </svg>
            </div>
            <h3 class="text-lg font-bold mb-2">{{ feature.title }}</h3>
            <p class="text-sm text-gray-500 leading-relaxed">{{ feature.description }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- How It Works -->
    <section id="how-it-works" class="py-24 bg-gray-50">
      <div class="max-w-6xl mx-auto px-6">
        <div class="text-center mb-14">
          <h2 class="text-3xl md:text-4xl font-extrabold tracking-tight mb-3">How It Works</h2>
          <p class="text-gray-500">Get up and running in four simple steps.</p>
        </div>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-8">
          <div v-for="step in steps" :key="step.number" class="flex gap-5 items-start">
            <div class="shrink-0 w-12 h-12 bg-gray-900 text-[#f7d686] rounded-xl flex items-center justify-center font-extrabold text-base">
              {{ step.number }}
            </div>
            <div>
              <h3 class="text-lg font-bold mb-1">{{ step.title }}</h3>
              <p class="text-sm text-gray-500 leading-relaxed">{{ step.description }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Wearable Device -->
    <section class="py-24 bg-gray-900 text-white">
      <div class="max-w-6xl mx-auto px-6 flex flex-col md:flex-row items-center gap-16 text-center md:text-left">
        <div class="flex-1">
          <h2 class="text-3xl md:text-4xl font-extrabold tracking-tight text-[#f7d686] mb-6">The Wearable Device</h2>
          <p class="text-gray-300 leading-relaxed mb-6">
            A lightweight, wearable unit powered by dual ESP32-CAM modules. It connects to the WalkSense app
            via WiFi and provides real-time obstacle detection using a YOLO-based model. Detected objects are
            announced through text-to-speech on the PWD's phone.
          </p>
          <ul class="space-y-3">
            <li v-for="item in [
              'Dual ESP32-CAM for wide-angle coverage',
              'Physical distress button for emergencies',
              'WiFi connectivity to the mobile app',
              'Lightweight and wearable design',
              'Real-time object detection with voice feedback',
            ]" :key="item" class="text-sm text-gray-300 flex items-center gap-3 justify-center md:justify-start">
              <span class="w-2 h-2 bg-[#f7d686] rounded-full shrink-0"></span>
              {{ item }}
            </li>
          </ul>
        </div>
        <div class="shrink-0">
          <div class="w-60 h-60 border-2 border-dashed border-gray-600 rounded-3xl flex flex-col items-center justify-center gap-3 text-gray-500 text-sm">
            <svg width="80" height="80" fill="none" stroke="currentColor" viewBox="0 0 24 24" class="opacity-40">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z"/>
            </svg>
            <span>ESP32-CAM Module</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Download CTA -->
    <section id="download" class="py-24 bg-gradient-to-br from-[#f7d686] to-[#f5c84e] text-center">
      <div class="max-w-6xl mx-auto px-6 flex flex-col items-center">
        <h2 class="text-3xl md:text-4xl font-extrabold tracking-tight mb-4">Ready to Get Started?</h2>
        <p class="text-lg text-gray-700 mb-9">
          Download WalkSense and give your loved one the freedom to walk safely.
        </p>
        <a href="/storage/Walksense.apk" class="bg-gray-900 text-white font-semibold px-12 py-4.5 rounded-full text-lg hover:bg-gray-700 transition-colors">
          Download for Android
        </a>
        <p class="mt-5 text-sm text-gray-500">
          Android 8.0 or higher required. You may need to enable "Install from Unknown Sources" in your device settings.
        </p>
      </div>
    </section>

    <!-- Footer -->
    <footer class="py-8 bg-gray-950 text-gray-400">
      <div class="max-w-6xl mx-auto px-6 flex flex-col md:flex-row items-center justify-between gap-3">
        <div class="flex items-center gap-2.5 text-white font-bold">
          <img src="/logo.png" alt="WalkSense" class="w-7 h-7 rounded-full" />
          <span>WalkSense</span>
        </div>
        <p class="text-sm">A Capstone Project &middot; {{ currentYear }}</p>
      </div>
    </footer>
  </div>
</template>
