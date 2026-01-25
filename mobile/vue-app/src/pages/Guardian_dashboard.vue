<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const menuOpen = ref(false)

const logout = () => {
  localStorage.removeItem('token')
  router.push('/login?role=guardian')
}

const goAccount = () => {
  router.push('/account')
}
</script>

<template>
  <div class="min-h-screen bg-gray-100">

    <header class="bg-white shadow-md px-4 py-3 flex items-center justify-between">
      <h1 class="text-lg font-bold text-gray-800">Guardian Dashboard</h1>

      <button @click="menuOpen = !menuOpen" class="text-gray-700 text-2xl">
        ☰
      </button>
    </header>

    <!-- ☰ Slide Menu -->
    <div v-if="menuOpen" class="absolute right-4 mt-2 w-40 bg-white rounded-xl shadow-lg border z-50">
      <button @click="goAccount" class="block w-full text-left px-4 py-3 hover:bg-gray-100">
        Account
      </button>
      <button @click="logout" class="block w-full text-left px-4 py-3 text-red-600 hover:bg-gray-100">
       Logout
      </button>
    </div>


    <div class="p-4 space-y-4">

      <div class="bg-white rounded-2xl shadow p-4">
        <h2 class="font-semibold text-gray-800 mb-2">PWD Status</h2>
        <p class="text-sm text-gray-600">Name: Juan Dela Cruz</p>
        <p class="text-sm text-gray-600">Last Updated: 2 mins ago</p>
        <p class="text-green-600 font-medium mt-1">● Online</p>
      </div>


      <div class="bg-white rounded-2xl shadow p-4">
        <h2 class="font-semibold text-gray-800 mb-2">Live Location</h2>
        <div class="h-48 bg-gray-200 rounded-xl flex items-center justify-center text-gray-500">
          Map will appear here
        </div>
      </div>

      <div class="bg-white rounded-2xl shadow p-4">
        <h2 class="font-semibold text-gray-800 mb-2">Alerts</h2>
        <p class="text-sm text-gray-600">No alerts right now</p>
      </div>

    </div>

  </div>
</template>
