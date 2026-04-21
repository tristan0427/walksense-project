import {Capacitor, CapacitorHttp} from '@capacitor/core';
import {Geolocation} from '@capacitor/geolocation';
import axios from 'axios';
import BackgroundGeolocation from "@/native/backgroundGeolocation.ts";
import type { Position } from '@capacitor/geolocation';

// Configure axios with proper base URL
axios.defaults.baseURL = import.meta.env.VITE_API_BASE_URL;

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
    private readonly debugLogs = false;

    /**
     * Start background location tracking
     */
    async startTracking(): Promise<void> {
        if (this.debugLogs) {
            console.log('Starting location tracking...');
            console.log('Platform:', Capacitor.getPlatform());
            console.log('Is Native?', Capacitor.isNativePlatform());
        }

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

            if (this.debugLogs) {
                console.log('Requesting background location permissions...');
            }


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
                        if (this.debugLogs) {
                            console.log('Background location update:', location);
                        }
                        await this.sendLocationUpdate(location);
                    }
                }
            );

            if (this.debugLogs) {
                console.log('Background tracking started with ID:', this.watcherId);
            }
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
        if (this.debugLogs) {
            console.log('Starting browser geolocation (fallback)...');
        }

        try {
            // Get initial location
            const position = await this.getCurrentLocation();
            if (this.debugLogs) {
                console.log('Initial browser location:', position.coords);
            }

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
                        if (this.debugLogs) {
                            console.log('Browser location update:', position.coords);
                        }
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
            if (this.debugLogs) {
                console.log('Browser tracking started');
            }

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
            if (this.debugLogs) {
                console.log('Tracking stopped');
            }
        } catch (error) {
            console.error('Failed to stop tracking:', error);
        }
    }

    /**
     * Get current location once
     */
    async getCurrentLocation(): Promise<Position> {
        if (this.debugLogs) {
            console.log('Getting current location...');
        }
        try {
            const position = await Geolocation.getCurrentPosition({
                enableHighAccuracy: true,
                timeout: 30000,
                maximumAge: 10000,
            });
            if (this.debugLogs) {
                console.log('Current location (high accuracy):', position.coords);
            }
            return position;
        } catch (err) {
            console.warn('High accuracy failed, trying low accuracy...', err);
            const position = await Geolocation.getCurrentPosition({
                enableHighAccuracy: false,
                timeout: 15000,
                maximumAge: 30000,
            });
            if (this.debugLogs) {
                console.log('Current location (low accuracy):', position.coords);
            }
            return position;
        }
    }

    /**
     * Send location update to backend
     */
    private async sendLocationUpdate(location: BackgroundLocation): Promise<void> {
        try {
            if (this.debugLogs) {
                console.log('Sending location to server...');
                console.log('API Base URL:', axios.defaults.baseURL);
            }

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
                    if (this.debugLogs) {
                        console.log('Battery level:', batteryLevel);
                    }
                }
            } catch (e) {
                if (this.debugLogs) {
                    console.log('Battery API not available');
                }
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

            if (this.debugLogs) {
                console.log('Payload:', payload);
            }

            const endpoint = (axios.defaults.baseURL || '') + '/api/location';
            
            // Use native CapacitorHttp to bypass Mixed Content restrictions
            const response = await CapacitorHttp.post({
                url: endpoint,
                headers: {
                    'Authorization': `Bearer ${token}`,
                    'Content-Type': 'application/json',
                    'Accept': 'application/json',
                },
                data: payload,
                connectTimeout: 10000,
                readTimeout: 10000,
            });

            if (response.status >= 200 && response.status < 300) {
                if (this.debugLogs) {
                    console.log('Location sent successfully:', response.data);
                }
            } else {
                throw new Error(`HTTP ${response.status}: Failed to send location`);
            }
        } catch (error: any) {
            console.error('Failed to send location:', {
                message: error.message || 'Unknown error',
                details: error
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