import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface ObjectDetectionPlugin {
    loadModel(): Promise<{ success: boolean }>;

    startESP32Stream(options?: { ip?: string }): Promise<{ success: boolean }>;

    startDayStream(options: { ip: string }): Promise<{ success: boolean }>;

    startNightStream(options: { ip: string }): Promise<{ success: boolean }>;

    switchActiveCamera(options: { camera: 'day' | 'night' }): Promise<{ success: boolean; activeCamera: string }>;

    detectFromStream(options: {
        confidence?: number;
        includeFrame?: boolean;
    }): Promise<{
        success: boolean;
        frame?: string;
        activeCamera?: string;
        nearest?: {
            class: string;
            distance: string;
            direction: string;
            confidence: number;
            camera: string;
            imminent: boolean;
        };
    }>;

    stopESP32Stream(): Promise<{ success: boolean }>;

    unloadModel(): Promise<{ success: boolean }>;

    scanHotspotNetwork(options?: {
        port?: number;
        path?: string;
        timeout?: number;
    }): Promise<{
        success: boolean;
        boards: Array<{ board: string; ip: string; ble_name?: string }>;
    }>;

    addListener(
        eventName: 'streamConnected',
        listenerFunc: (data: { status: string }) => void
    ): Promise<PluginListenerHandle> & PluginListenerHandle;

    addListener(
        eventName: 'streamError',
        listenerFunc: (data: { error: string }) => void
    ): Promise<PluginListenerHandle> & PluginListenerHandle;

    removeAllListeners(): Promise<void>;
}

const ObjectDetection = registerPlugin<ObjectDetectionPlugin>('ObjectDetection');

export default ObjectDetection;