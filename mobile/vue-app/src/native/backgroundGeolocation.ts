import { Capacitor } from '@capacitor/core';

const BackgroundGeolocation = {
    addWatcher: async (options: any, callback: any) => {
        if (Capacitor.getPlatform() === 'web') {
            console.log('Web platform - BackgroundGeolocation unavailable');
            return null;
        }

        try {
            // @ts-ignore
            const { BackgroundGeolocation: BGGeo } = window;
            return await BGGeo?.addWatcher(options, callback);
        } catch (error) {
            console.error('BackgroundGeolocation error:', error);
            return null;
        }
    },

    removeWatcher: async (options: any) => {
        if (Capacitor.getPlatform() === 'web') return;

        try {
            // @ts-ignore
            const { BackgroundGeolocation: BGGeo } = window;
            return await BGGeo?.removeWatcher(options);
        } catch (error) {
            console.error('Remove watcher error:', error);
        }
    }
};

export default BackgroundGeolocation;