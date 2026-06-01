import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface ObjectDetectionPlugin {
    loadModel(): Promise<{ success: boolean }>;

    startESP32Stream(options?: { ip?: string }): Promise<{ success: boolean }>;

    startDayStream(options: { ip: string }): Promise<{ success: boolean }>;

    startNightStream(options: { ip: string }): Promise<{ success: boolean }>;

    switchActiveCamera(options: { camera: 'day' | 'night' }): Promise<{ success: boolean; activeCamera: string }>;

    setDiagnosticsMode(options: { enabled: boolean }): Promise<{ success: boolean; diagnosticsEnabled: boolean }>;

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
            avoidance?: string;  // "left" | "right" | "both" | "blocked"
            stable?: boolean;
            // Bounding box coordinates (320x320 model space) for demo mode overlay
            x1?: number;
            y1?: number;
            x2?: number;
            y2?: number;
        };
        metrics?: {
            inferenceMs: number;
            detectMs: number;
            diagnosticsEnabled: boolean;
        };
    }>;

    stopESP32Stream(): Promise<{ success: boolean }>;

    unloadModel(): Promise<{ success: boolean }>;

    // Demo mode preview — emits camera frames as Base64 JPEG for canvas overlay
    startPreview(): Promise<{ success: boolean }>;
    stopPreview(): Promise<{ success: boolean }>;

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

    addListener(
        eventName: 'previewFrame',
        listenerFunc: (data: { frame: string | null; camera?: string }) => void
    ): Promise<PluginListenerHandle> & PluginListenerHandle;

    removeAllListeners(): Promise<void>;
}

const ObjectDetection = registerPlugin<ObjectDetectionPlugin>('ObjectDetection');

export default ObjectDetection;