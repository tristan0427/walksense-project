import {Capacitor} from '@capacitor/core';
import {Geolocation} from '@capacitor/geolocation';
import axios from 'axios';
import BackgroundGeolocation from "@/native/backgroundGeolocation.ts";
import type { Position } from '@capacitor/geolocation';

// Configure axios with proper base URL
axios.defaults.baseURL = import.meta.env.VITE_API_BASE_URL || 'http://172.23.172.98:8000';

// Set auth token
const token = localStorage.getItem('token');
if (token) {
    axios.defaults.headers.common['Authorization'] = `Bearer ${token}`;
}

interface BackgroundLocation {
    latitude: number;
    longitude: number;
    accuracy: number;
    altitude?: number;
    speed?: number;
    bearing?: number;
}

class LocationService {
    private watcherId: string | null = null;
    private isTracking = false;

    /**
     * Start background location tracking
     */
    async startTracking(): Promise<void> {
        console.log('Starting location tracking...');
        console.log('Platform:', Capacitor.getPlatform());
        console.log('Is Native?', Capacitor.isNativePlatform());

        // For development in browser, use fallback
        if (!Capacitor.isNativePlatform()) {
            console.warn('Running in browser - using fallback geolocation');
            return this.startBrowserTracking();
        }

        try {
            if (this.watcherId) {
                await this.stopTracking();
            }

            const initialLocation = await this.getCurrentLocation();
            await this.sendLocationUpdate({
                latitude: initialLocation.coords.latitude,
                longitude: initialLocation.coords.longitude,
                accuracy: initialLocation.coords.accuracy,
                altitude: initialLocation.coords.altitude || undefined,
                speed: initialLocation.coords.speed || undefined,
                bearing: initialLocation.coords.heading || undefined,
            });

            this.isTracking = true;

            console.log('Requesting background location permissions...');


            //The import has been on Comment state because that will work only on android, uncomment it when  testing on android to work
            // Just ignore this redlines, still work on browser when testing
            this.watcherId = await BackgroundGeolocation.addWatcher(
                {
                    backgroundMessage: 'WalkSense is tracking your location for safety.',
                    backgroundTitle: 'Safety Tracking Active',
                    requestPermissions: true,
                    stale: false,
                    distanceFilter: 20,
                    timeout: 30000,
                    maximumAge: 5000,
                    enableHighAccuracy: true,
                },
                async (location:any, error:any) => {
                    if (error) {
                        console.error('Location error:', error);

                        if (error.code === 'NOT_AUTHORIZED') {
                            console.error('Location permission denied by user');
                            alert('Please enable location permissions for safety tracking');
                        }
                        return;
                    }

                    if (location) {
                        console.log('Background location update:', location);
                        await this.sendLocationUpdate(location);
                    }
                }
            );

            console.log('Background tracking started with ID:', this.watcherId);
        } catch (error) {
            console.error('Failed to start tracking:', error);
            this.isTracking = false;
            throw error;
        }
    }

    /**
     * Fallback for browser testing (development only)
     */
    private async startBrowserTracking(): Promise<void> {
        console.log('Starting browser geolocation (fallback)...');

        try {
            // Get initial location
            const position = await this.getCurrentLocation();
            console.log('Initial browser location:', position.coords);

            // Send initial location
            await this.sendLocationUpdate({
                latitude: position.coords.latitude,
                longitude: position.coords.longitude,
                accuracy: position.coords.accuracy,
                altitude: position.coords.altitude || undefined,
                speed: position.coords.speed || undefined,
                bearing: position.coords.heading || undefined,
            });

            // Watch for changes (browser geolocation)
            this.watcherId = await Geolocation.watchPosition(
                {
                    enableHighAccuracy: true,
                    timeout: 10000,
                    maximumAge: 0,
                },
                (position, error) => {
                    if (error) {
                        console.error('Browser location error:', error);
                        return;
                    }

                    if (position) {
                        console.log('Browser location update:', position.coords);
                        this.sendLocationUpdate({
                            latitude: position.coords.latitude,
                            longitude: position.coords.longitude,
                            accuracy: position.coords.accuracy,
                            altitude: position.coords.altitude || undefined,
                            speed: position.coords.speed || undefined,
                            bearing: position.coords.heading || undefined,
                        });
                    }
                }
            );
            this.isTracking = true;
            console.log('Browser tracking started');

        } catch (error) {
            console.error('Browser geolocation failed:', error);
            throw error;
        }
    }

    /**
     * Stop tracking
     */
    async stopTracking(): Promise<void> {
        if (!this.watcherId) return;

        try {
            if (Capacitor.isNativePlatform()) {
                await BackgroundGeolocation.removeWatcher({ id: this.watcherId });
            } else {
                await Geolocation.clearWatch({ id: this.watcherId });
            }

            this.watcherId = null;
            this.isTracking = false;
            console.log('Tracking stopped');
        } catch (error) {
            console.error('Failed to stop tracking:', error);
        }
    }

    /**
     * Get current location once
     */
    async getCurrentLocation(): Promise<Position> {
        console.log('Getting current location...');
        const position = await Geolocation.getCurrentPosition({
            enableHighAccuracy: true,
            timeout: 10000,
            maximumAge: 0,
        });
        console.log('Current location:', position.coords);
        return position;
    }

    /**
     * Send location update to backend
     */
    private async sendLocationUpdate(location: BackgroundLocation): Promise<void> {
        try {
            console.log('Sending location to server...');
            console.log('API Base URL:', axios.defaults.baseURL);

            const token = localStorage.getItem('token');
            if (!token) {
                console.error('No auth token found');
                return;
            }

            let batteryLevel: number | null = null;
            try {
                if ('getBattery' in navigator) {
                    const battery = await (navigator as any).getBattery();
                    batteryLevel = Math.round(battery.level * 100);
                    console.log('Battery level:', batteryLevel);
                }
            } catch (e) {
                console.log('Battery API not available');
            }

            const payload = {
                latitude: location.latitude,
                longitude: location.longitude,
                accuracy: location.accuracy,
                altitude: location.altitude,
                speed: location.speed,
                heading: location.bearing,
                battery_level: batteryLevel,
            };

            console.log('Payload:', payload);

            const response = await axios.post('/api/location', payload, {
                headers: {
                    'Authorization': `Bearer ${token}`,
                    'Content-Type': 'application/json',
                    'Accept': 'application/json',
                }
            });

            console.log('Location sent successfully:', response.data);
        } catch (error: any) {
            console.error('Failed to send location:', {
                status: error.response?.status,
                message: error.response?.data?.message || error.message,
                errors: error.response?.data?.errors,
                url: error.config?.url,
            });
        }
    }

    /**
     * Check if tracking is active
     */
    isTrackingActive(): boolean {
        return this.isTracking;
    }
}

export default new LocationService();