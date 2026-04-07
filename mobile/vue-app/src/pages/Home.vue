<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const handleLogin = (type: 'Guardian' | 'PWD') => {
  router.push({
    name: 'Login',
    params: { type }
  })
}
</script>


<template>
  <div class="min-h-screen flex flex-col items-center justify-center bg-[#f7d686] px-3">
    <div class="w-50 h-50 bg-black rounded-full flex items-center justify-center shadow-2xl ring-4 ring-black/10 mb-12">
      <img src="/logo.png" class="w-full h-full object-contain p-4" alt="WalkSense Logo"/>
    </div>

    <div class="w-full max-w-md space-y-5">
      <button @click="handleLogin('Guardian')"
              class="w-full py-4 rounded-full border border-black text-lg font-bold bg-transparent shadow-sm hover:bg-black/5 active:scale-[0.98] transition-all">
        I'm a Guardian
      </button>

      <button @click="handleLogin('PWD')"
              class="w-full py-4 rounded-full bg-black text-white text-lg font-bold shadow-md hover:bg-gray-800 active:scale-[0.98] transition-all">
        I'm a PWD
      </button>
    </div>

    <p class="mt-8 text-sm">
      Don't have an account yet?
      <router-link to="/register" class="text-gray-900 underline font-bold hover:no-underline">
        Register here
      </router-link>
    </p>
  </div>
</template>