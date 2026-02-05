declare module '@capacitor-community/background-geolocation' {
    export interface BackgroundGeolocationOptions {
        backgroundMessage?: string;
        backgroundTitle?: string;
        requestPermissions?: boolean;
        stale?: boolean;
        distanceFilter?: number;
    }

    export interface Location {
        latitude: number;
        longitude: number;
        accuracy: number;
        altitude?: number;
        speed?: number;
        bearing?: number;
    }

    export interface BackgroundGeolocationError {
        code: string;
        message: string;
    }

    export interface BackgroundGeolocation {
        addWatcher(
            options: BackgroundGeolocationOptions,
            callback: (location: Location | null, error: BackgroundGeolocationError | null) => void
        ): Promise<string>;

        removeWatcher(options: { id: string }): Promise<void>;
    }

    const BackgroundGeolocation: BackgroundGeolocation;
    export default BackgroundGeolocation;
}