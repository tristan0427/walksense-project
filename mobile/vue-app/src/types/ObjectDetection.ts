import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface ObjectDetectionPlugin {
    loadModel(): Promise<{ success: boolean }>;

    startESP32Stream(): Promise<{ success: boolean }>;

    detectFromStream(options: {
        confidence?: number;
    }): Promise<{
        success: boolean;
        nearest?: {
            class: string;
            distance: string;
            direction: string;
            confidence: number;
        };
    }>;

    stopESP32Stream(): Promise<{ success: boolean }>;

    unloadModel(): Promise<{ success: boolean }>;

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