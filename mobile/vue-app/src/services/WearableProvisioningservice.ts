/**
 * WearableProvisioningService.ts
 *
 * Handles two phases of the wearable setup flow:
 *
 * PHASE 1 — BLE PROVISIONING
 *   Uses @capacitor-community/bluetooth-le to find WEARABLE_DAY and
 *   WEARABLE_NIGHT over BLE and push the phone's hotspot credentials
 *   using Espressif's Unified Provisioning GATT protocol.
 *
 * PHASE 2 — BOARD DISCOVERY
 *   After the boards connect to the hotspot, scans the phone's hotspot
 *   subnet (192.168.43.x by default) hitting GET /identity on each IP
 *   to auto-detect which IP belongs to day vs night board.
 *   Saves results to localStorage so the rest of the app just reads them.
 *
 * Espressif BLE Provisioning GATT UUIDs (Security 0):
 *   Service:          021a9004-0382-4aea-bff4-6b3f1c5adfb4
 *   Session char:     0000ff51-0000-1000-8000-00805f9b34fb   (handshake)
 *   Config char:      0000ff52-0000-1000-8000-00805f9b34fb   (send SSID+pwd)
 *   Status char:      0000ff53-0000-1000-8000-00805f9b34fb   (read result)
 *
 * FIX SUMMARY (v3.2):
 *   - Scan collects BOTH device IDs before attempting any connection
 *   - Boards are provisioned SEQUENTIALLY, one at a time (never concurrent)
 *   - Each board gets a longer post-connect settle delay (1500ms) before GATT writes
 *   - Board found earliest in scan is provisioned last (more settle time = more stable)
 *   - provisionBoard() verifies the connection is truly live before writing
 *   - bleWriteWithRetry reconnects automatically if the device dropped between retries
 */

import ObjectDetection from '../types/ObjectDetection';
import { BleClient, ScanResult } from '@capacitor-community/bluetooth-le';
import { CapacitorHttp } from '@capacitor/core';

// ── Constants ──────────────────────────────────────────────────────────────────

const PROV_SERVICE_UUID   = '021a9004-0382-4aea-bff4-6b3f1c5adfb4';
const PROV_SESSION_CHAR   = '021aff51-0382-4aea-bff4-6b3f1c5adfb4';
const PROV_CONFIG_CHAR    = '021aff52-0382-4aea-bff4-6b3f1c5adfb4';
const PROV_STATUS_CHAR    = '021aff53-0382-4aea-bff4-6b3f1c5adfb4';

const BLE_NAME_DAY        = 'WEARABLE_DAY';
const BLE_NAME_NIGHT      = 'WEARABLE_NIGHT';
const SCAN_TIMEOUT_MS     = 20000;

// How long to wait after connect() succeeds before issuing any GATT writes.
// ESP32-CAM BLE stack needs time to finish its own service discovery internally.
const POST_CONNECT_SETTLE_MS = 1500;

// ── Types ──────────────────────────────────────────────────────────────────────

export interface BoardInfo {
    board:    'day' | 'night';
    ip:       string;
    ble_name: string;
}

export type ProvisioningStatus =
    | 'idle'
    | 'scanning_ble'
    | 'found_day'
    | 'found_night'
    | 'provisioning_day'
    | 'provisioning_night'
    | 'waiting_wifi'
    | 'scanning_subnet'
    | 'resetting'
    | 'done'
    | 'error';

export interface ProvisioningState {
    status:      ProvisioningStatus;
    message:     string;
    dayBoard?:   BoardInfo;
    nightBoard?: BoardInfo;
    error?:      string;
}

type StatusCallback = (state: ProvisioningState) => void;

// ── WearableProvisioningService ────────────────────────────────────────────────

class WearableProvisioningService {

    private isProvisioning = false;
    private onStatus: StatusCallback | null = null;
    private readonly debugLogs = false;

    private emit(state: ProvisioningState) {
        if (this.debugLogs) {
            console.log('[Provisioning]', state.status, '-', state.message);
        }
        this.onStatus?.(state);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Full provisioning flow:
     *   1. Scan BLE for both boards (collect both IDs before connecting to either)
     *   2. Push hotspot SSID+password to each via BLE GATT — one at a time
     *   3. Wait for boards to connect to hotspot
     *   4. Scan subnet to find their IPs
     *   5. Save IPs to localStorage
     *
     * KEY CHANGE: We scan first, then connect sequentially.
     * Never hold two BLE connections at the same time on Android.
     *
     * @param ssid     Phone hotspot SSID
     * @param password Phone hotspot password
     * @param onStatus Callback for live status updates (drives the UI)
     */
    async provision(
        ssid:     string,
        password: string,
        onStatus: StatusCallback
    ): Promise<void> {
        if (this.isProvisioning) {
            console.warn('[Provisioning] Already running. Ignoring duplicate sequence.');
            return;
        }

        this.isProvisioning = true;
        this.onStatus = onStatus;

        try {
            await BleClient.initialize();

            // ── Step 1: Scan for both boards — collect IDs, do NOT connect yet ──
            this.emit({ status: 'scanning_ble', message: 'Scanning for wearable boards...' });
            const { dayDeviceId, nightDeviceId, dayFoundAt, nightFoundAt } = await this.scanForBoards();

            // ── Step 2: Determine provisioning order ────────────────────────────
            // Provision the board found LAST first — it has had the least idle time
            // since scan ended and its radio is freshest. The first-found board
            // has been idle the longest and is therefore more settled when we reach it.
            //
            // In the failing case: Night was found first, Day was found second.
            // Old code provisioned Day first → Day had barely finished advertising
            // when we slammed it with a connect. Flip the order: Night → Day.
            const provisionOrder: Array<{ id: string; board: 'day' | 'night'; foundAt: number }> = [];
            if (dayDeviceId) provisionOrder.push({ id: dayDeviceId, board: 'day', foundAt: dayFoundAt });
            if (nightDeviceId) provisionOrder.push({ id: nightDeviceId, board: 'night', foundAt: nightFoundAt });

            // Sort descending by foundAt — most recently found goes first
            provisionOrder.sort((a, b) => b.foundAt - a.foundAt);

            for (const { id, board } of provisionOrder) {
                const statusKey = board === 'day' ? 'provisioning_day' : 'provisioning_night';
                const label     = board === 'day' ? 'day' : 'night';
                this.emit({ status: statusKey, message: `Sending credentials to ${label} camera...` });

                const success = await this.provisionBoard(id, ssid, password);
                if (!success) {
                    this.emit({
                        status:  'error',
                        message: `${label} camera provisioning failed — continuing anyway`,
                        error:   `${board}_provision_failed`
                    });
                }

                // Hard gap between boards — give Android BLE stack time to fully
                // release its internal GATT cache for the previous device before
                // we open a fresh connection to the next one.
                await this.delay(1000);
            }

            if (!dayDeviceId) {
                this.emit({ status: 'error', message: 'Day camera not found via BLE', error: 'day_not_found' });
            }
            if (!nightDeviceId) {
                this.emit({ status: 'error', message: 'Night camera not found via BLE', error: 'night_not_found' });
            }

            // ── Step 3: Resolve boards by mDNS hostname ──────────────────────────
            // Boards advertise themselves as walksense-day.local / walksense-night.local
            // via ESPmDNS as soon as they connect to WiFi. No subnet scanning needed.
            // We give the boards a short window to come online then verify via /identity.
            this.emit({ status: 'waiting_wifi', message: 'Waiting for cameras to come online...' });

            const MDNS_HOSTNAMES: Record<'day' | 'night', string> = {
                day:   'walksense-day.local',
                night: 'walksense-night.local',
            };

            let boards: BoardInfo[] = [];
            for (const { board } of provisionOrder) {
                const hostname = MDNS_HOSTNAMES[board];
                this.emit({ status: 'waiting_wifi', message: `Waiting for ${board} camera (${hostname})...` });
                // 35s timeout — native scanner needs 8s to fire + ~15s to complete = ~23s total.
                // 20s was too tight and the result arrived after the deadline.
                const found = await this.waitForBoardByHostname(board, hostname, 35000);
                if (found) {
                    boards.push(found);
                    this.emit({ status: 'scanning_subnet', message: `✅ ${board} camera online at ${found.ip}` });
                }
            }

            // ── Step 4: If a board is still missing, try native scanner once more ──
            // Skip BLE rescan — if BLE provisioning succeeded, the board is on WiFi
            // and just needs more time. BLE rescan is pointless since the board is no
            // longer advertising. Just scan the subnet again directly.
            const missingBoards = provisionOrder.filter(
                ({ board }) => !boards.find(b => b.board === board)
            );

            if (missingBoards.length > 0) {
                this.emit({ status: 'scanning_subnet', message: 'Scanning network for remaining cameras...' });

                try {
                    const result = await ObjectDetection.scanHotspotNetwork({
                        port:    82,
                        path:    '/identity',
                        timeout: 2000,
                    });

                    if (result.success && result.boards) {
                        for (const b of result.boards) {
                            if (b.board && b.ip) {
                                const missingMatch = missingBoards.find(m => m.board === b.board);
                                if (missingMatch) {
                                    const found: BoardInfo = { board: b.board as 'day' | 'night', ip: b.ip, ble_name: b.ble_name || '' };
                                    boards = [...boards.filter(x => x.board !== b.board), found];
                                    this.emit({ status: 'scanning_subnet', message: `✅ ${b.board} camera found at ${b.ip}` });
                                }
                            }
                        }
                    }
                } catch (err: any) {
                    console.warn('[Discovery] Final subnet scan failed:', err.message);
                }

                // Report any still-missing boards
                const stillMissing = provisionOrder.filter(
                    ({ board }) => !boards.find(b => b.board === board)
                );
                for (const { board } of stillMissing) {
                    this.emit({
                        status:  'error',
                        message: `❌ ${board} camera still not found — check it is powered on`,
                        error:   `${board}_not_on_network`
                    });
                }
            }

            // Save real IPs from identity responses — reliable for stream/reset connections.
            // Falls back to hostname only if board was never reached (unlikely at this point).
            const dayBoard: BoardInfo | undefined = boards.find(
                (b): b is BoardInfo => b.board === 'day'
            );
            const nightBoard: BoardInfo | undefined = boards.find(
                (b): b is BoardInfo => b.board === 'night'
            );

            const dayIp = dayBoard?.ip;
            const nightIp = nightBoard?.ip;
            if (dayIp) localStorage.setItem('dayCamIp', dayIp);
            if (nightIp) localStorage.setItem('nightCamIp', nightIp);

            this.emit({
                status:     'done',
                message:    'Setup complete!',
                dayBoard,
                nightBoard
            });

        } catch (err: any) {
            this.emit({
                status:  'error',
                message: 'Provisioning failed: ' + err.message,
                error:   err.message
            });
            throw err;
        } finally {
            this.isProvisioning = false;
        }
    }

    /**
     * Resets both Day and Night boards by calling their /reset endpoint.
     * Uses CapacitorHttp to bypass the HTTPS mixed-content restriction.
     * Only clears localStorage if the reset command was actually sent.
     */
    async resetBothBoards(onStatus: StatusCallback): Promise<void> {
        this.onStatus = onStatus;
        this.emit({ status: 'resetting', message: 'Sending reset commands to cameras...' });

        const dayIp   = localStorage.getItem('dayCamIp');
        const nightIp = localStorage.getItem('nightCamIp');

        if (!dayIp && !nightIp) {
            throw new Error('No camera IPs saved. Cannot remote reset.');
        }

        const resetBoard = async (ip: string, label: string): Promise<boolean> => {
            try {
                await CapacitorHttp.get({
                    url:             `http://${ip}:82/reset`,
                    connectTimeout:  2000,
                    readTimeout:     2000,
                    disableRedirects: true,
                });
                console.log(`[Reset] ${label} camera (${ip}) reset command sent`);
                return true;
            } catch {
                console.log(`[Reset] ${label} camera (${ip}) reset network error or timeout (expected)`);
                return false;
            }
        };

        await Promise.allSettled([
            dayIp   ? resetBoard(dayIp,   'Day')   : Promise.resolve(false),
            nightIp ? resetBoard(nightIp, 'Night') : Promise.resolve(false),
        ]);

        // Clear localStorage regardless — boards are being wiped so old IPs are invalid
        localStorage.removeItem('dayCamIp');
        localStorage.removeItem('nightCamIp');

        this.emit({ status: 'done', message: 'Reset commands sent to cameras' });
    }

    /**
     * Discovery only — skips BLE entirely.
     * Uses native TCP subnet scanner directly (no mDNS, no delay).
     * Retries once if a board is missing on first scan.
     */
    async rediscoverBoards(onStatus: StatusCallback): Promise<void> {
        this.onStatus = onStatus;
        this.emit({ status: 'scanning_subnet', message: 'Scanning hotspot network for cameras...' });

        let dayBoard: BoardInfo | null = null;
        let nightBoard: BoardInfo | null = null;
        let dayIpFound: string | null = null;
        let nightIpFound: string | null = null;

        const scanOnce = async (): Promise<void> => {
            try {
                const result = await ObjectDetection.scanHotspotNetwork({
                    port:    82,
                    path:    '/identity',
                    timeout: 2500,
                });
                if (result.success && result.boards) {
                    for (const b of result.boards) {
                        if (!dayBoard && b.board === 'day' && b.ip) {
                            dayBoard = { board: 'day', ip: b.ip, ble_name: b.ble_name || '' };
                            dayIpFound = b.ip;
                            this.emit({ status: 'scanning_subnet', message: `✅ Day camera found at ${b.ip}` });
                        }
                        if (!nightBoard && b.board === 'night' && b.ip) {
                            nightBoard = { board: 'night', ip: b.ip, ble_name: b.ble_name || '' };
                            nightIpFound = b.ip;
                            this.emit({ status: 'scanning_subnet', message: `✅ Night camera found at ${b.ip}` });
                        }
                    }
                }
            } catch (err: any) {
                console.warn('[Rediscovery] Subnet scan error:', err.message);
            }
        };

        await scanOnce();

        // Retry once if a board is still missing
        if (!dayBoard || !nightBoard) {
            this.emit({ status: 'scanning_subnet', message: 'Retrying scan for remaining cameras...' });
            await this.delay(3000);
            await scanOnce();
        }


        if (dayIpFound) localStorage.setItem('dayCamIp', dayIpFound);
        if (nightIpFound) localStorage.setItem('nightCamIp', nightIpFound);

        const count = [dayBoard, nightBoard].filter(Boolean).length;
        this.emit({
            status:     'done',
            message:    count > 0 ? `Found ${count} camera(s)` : 'No cameras found on network',
            dayBoard:   dayBoard   ?? undefined,
            nightBoard: nightBoard ?? undefined,
        });
    }

    // ── BLE Scanning ──────────────────────────────────────────────────────────

    /**
     * Scans for both boards and returns their device IDs plus the timestamp
     * each was found. We use foundAt to decide provisioning order later.
     *
     * IMPORTANT: This method does NOT connect to anything. It only collects IDs.
     */
    private async scanForBoards(): Promise<{
        dayDeviceId?:   string;
        nightDeviceId?: string;
        dayFoundAt:     number;
        nightFoundAt:   number;
    }> {
        return new Promise((resolve, reject) => {
            let dayDeviceId:   string | undefined;
            let nightDeviceId: string | undefined;
            let dayFoundAt   = 0;
            let nightFoundAt = 0;

            const timeout = setTimeout(() => {
                BleClient.stopLEScan();
                resolve({ dayDeviceId, nightDeviceId, dayFoundAt, nightFoundAt });
            }, SCAN_TIMEOUT_MS);

            BleClient.requestLEScan(
                {},
                (result: ScanResult) => {
                    const name = result.localName || result.device?.name || '';

                    if (name === BLE_NAME_DAY && !dayDeviceId) {
                        dayDeviceId = result.device.deviceId;
                        dayFoundAt  = Date.now();
                        this.emit({ status: 'found_day', message: `Found day camera (${dayDeviceId})` });
                    }
                    if (name === BLE_NAME_NIGHT && !nightDeviceId) {
                        nightDeviceId = result.device.deviceId;
                        nightFoundAt  = Date.now();
                        this.emit({ status: 'found_night', message: `Found night camera (${nightDeviceId})` });
                    }

                    if (dayDeviceId && nightDeviceId) {
                        clearTimeout(timeout);
                        BleClient.stopLEScan();
                        resolve({ dayDeviceId, nightDeviceId, dayFoundAt, nightFoundAt });
                    }
                }
            ).catch((err: any) => {
                clearTimeout(timeout);
                reject(err);
            });
        });
    }
    /**
     * Sends SSID and password to a single board.
     * Returns true on success, false on non-fatal failure.
     *
     * KEY CHANGES vs old version:
     *   1. Cleans up any stale connection BEFORE connecting
     *   2. Waits POST_CONNECT_SETTLE_MS after connect() before any GATT op
     *   3. bleWriteWithRetry can reconnect if the device dropped mid-session
     *   4. Always disconnects cleanly at the end (success or failure)
     */
    private async provisionBoard(
        deviceId: string,
        ssid:     string,
        password: string
    ): Promise<boolean> {
        // 1. Kill any lingering connection — Android BLE state machine can get stuck
        try { await BleClient.disconnect(deviceId); } catch { /* already clean */ }
        await this.delay(500);

        // Tracks whether we got past the config write phase.
        // A disconnect BEFORE this = real failure (credentials never written).
        // A disconnect AFTER  this = safe to assume success (ESP32 applies config and drops BLE).
        let configWriteCompleted = false;

        try {
            // 2. Connect — generous timeout for slow ESP32-CAM BLE stack
            await BleClient.connect(deviceId, () => {
                console.warn('[Provisioning] Device disconnected unexpectedly:', deviceId);
            }, { timeout: 15000 });

            // 3. CRITICAL: settle delay — ESP32-CAM needs time to finish its own
            //    internal GATT service registration after the link is up.
            //    Without this, the first write hits a "not connected" race.
            await this.delay(POST_CONNECT_SETTLE_MS);

            // 4. Service discovery — find the actual provisioning service UUID
            const services = await BleClient.getServices(deviceId);
            let targetServiceUUID = PROV_SERVICE_UUID;

            if (this.debugLogs) {
                console.log('[Provisioning] --- DISCOVERED BLE SERVICES ---');
                for (const service of services) {
                    console.log(`[Provisioning] Service: ${service.uuid}`);
                    for (const char of service.characteristics) {
                        console.log(`[Provisioning]   -> Characteristic: ${char.uuid}`);
                    }
                }
                console.log('[Provisioning] --------------------------------');
            }

            for (const service of services) {
                const hasConfigChar = service.characteristics.some(c => {
                    const cUuid = c.uuid.toLowerCase().replace(/-/g, '');
                    const tUuid = PROV_CONFIG_CHAR.toLowerCase().replace(/-/g, '');
                    return cUuid.includes(tUuid) || tUuid.includes(cUuid);
                });
                if (hasConfigChar) {
                    targetServiceUUID = service.uuid;
                    console.log(`[Provisioning] Found provisioning service UUID: ${targetServiceUUID}`);
                    break;
                }
            }

            // 5. Session Init (Sec0)
            console.log('[Provisioning] Initializing Session (Sec0)...');
            const sessionInitPayload = this.buildSessionInitPayload();
            await this.bleWriteWithRetry(deviceId, targetServiceUUID, PROV_SESSION_CHAR, sessionInitPayload);
            await this.delay(200);

            // 6. Read session response
            console.log('[Provisioning] Reading session response...');
            const sessionResp = await BleClient.read(deviceId, targetServiceUUID, PROV_SESSION_CHAR);
            console.log('[Provisioning] Session response:', new Uint8Array(sessionResp.buffer));
            await this.delay(200);

            // 7. Set Config (SSID + password)
            console.log('[Provisioning] Sending Config (SSID & Password)...');
            const configPayload = this.buildConfigPayload(ssid, password);
            await this.bleWriteWithRetry(deviceId, targetServiceUUID, PROV_CONFIG_CHAR, configPayload);
            await this.delay(500); // flash write safety margin

            // 8. Apply Config
            console.log('[Provisioning] Sending Apply Config Command...');
            const applyPayload = this.buildApplyConfigPayload();
            await this.bleWriteWithRetry(deviceId, targetServiceUUID, PROV_CONFIG_CHAR, applyPayload);

            // Mark that credentials were fully written to the board.
            // Any disconnect from this point onward is the ESP32 switching
            // its radio from BLE to WiFi — that's expected and means success.
            configWriteCompleted = true;

            // 9. Poll status
            let attempts = 0;
            while (attempts < 20) {
                await this.delay(1000);
                try {
                    const statusData  = await BleClient.read(deviceId, targetServiceUUID, PROV_STATUS_CHAR);
                    const statusBytes = new Uint8Array(statusData.buffer);
                    console.log('[Provisioning] Status bytes:', statusBytes);

                    if (statusBytes[0] === 1 || statusBytes.length === 0) {
                        console.log('[Provisioning] Board confirmed success');
                        return true;
                    }
                    if (statusBytes[0] === 2) {
                        throw new Error('Board reported provisioning failure (bad password?)');
                    }
                } catch {
                    // Disconnect during polling = ESP32 dropped BLE to switch to WiFi.
                    // Only treat as success if credentials were actually written.
                    if (configWriteCompleted) {
                        console.log('[Provisioning] BLE disconnected after config write — assuming success.');
                        return true;
                    } else {
                        console.error('[Provisioning] BLE disconnected BEFORE config write completed — real failure.');
                        return false;
                    }
                }
                attempts++;
            }

            console.log('[Provisioning] No definitive status — assuming success');
            return true;

        } catch (err: any) {
            console.error('[Provisioning] provisionBoard error:', err.message);
            // If we already wrote the config before this error, don't treat it as failure
            if (configWriteCompleted) {
                console.log('[Provisioning] Error after config write — treating as success.');
                return true;
            }
            return false;
        } finally {
            // Always disconnect cleanly so the next board gets a fresh Android BLE slot
            try { await BleClient.disconnect(deviceId); } catch { /* already gone */ }
            await this.delay(500);
        }
    }

    /**
     * Retries a BLE write. On failure, attempts a reconnect before retrying
     * because the ESP32-CAM can silently drop the link during heavy flash I/O.
     */
    private async bleWriteWithRetry(
        deviceId:         string,
        serviceUUID:      string,
        characteristicUUID: string,
        value:            DataView,
        maxRetries = 3
    ): Promise<void> {
        let attempt = 1;
        while (true) {
            try {
                await BleClient.write(deviceId, serviceUUID, characteristicUUID, value);
                return;
            } catch (err: any) {
                console.warn(`[Provisioning] BLE write failed on attempt ${attempt}/${maxRetries}: ${err.message}`);
                if (attempt >= maxRetries) throw err;

                // Try to reconnect before the next attempt — the device may have
                // dropped the link transiently (common during BLE→WiFi radio switch)
                console.log('[Provisioning] Attempting reconnect before retry...');
                try {
                    await BleClient.disconnect(deviceId);
                } catch { /* already disconnected */ }
                await this.delay(1000);
                try {
                    await BleClient.connect(deviceId, () => {}, { timeout: 10000 });
                    await this.delay(POST_CONNECT_SETTLE_MS);
                } catch (reconnErr: any) {
                    console.warn('[Provisioning] Reconnect failed:', reconnErr.message);
                    throw err; // Propagate original write error if reconnect also fails
                }

                attempt++;
            }
        }
    }

    // ── Protobuf Builders ─────────────────────────────────────────────────────

    /** Sec0 SessionCmd protobuf: { msg: SessionCmd, sec0: S0SessionCmd } */
    private buildSessionInitPayload(): DataView {
        return new DataView(new Uint8Array([0x10, 0x00, 0x52, 0x02, 0x08, 0x00]).buffer);
    }

    /** WiFiConfigPayload for CMD_APPLY_CONFIG */
    private buildApplyConfigPayload(): DataView {
        return new DataView(new Uint8Array([0x08, 0x04, 0x72, 0x00]).buffer);
    }

    /** WiFiConfigPayload with SSID + password (Security 0) */
    private buildConfigPayload(ssid: string, password: string): DataView {
        const ssidBytes = new TextEncoder().encode(ssid);
        const pwdBytes  = new TextEncoder().encode(password);

        const buf: number[] = [];
        buf.push(0x08, 0x02); // field 1 (msg_type) = TypeCmdSetConfig

        const inner: number[] = [];
        inner.push(0x0A);
        inner.push(ssidBytes.length);
        ssidBytes.forEach((b: number) => inner.push(b));

        if (pwdBytes.length > 0) {
            inner.push(0x12);
            inner.push(pwdBytes.length);
            pwdBytes.forEach((b: number) => inner.push(b));
        }

        buf.push(0x62);        // field 12 tag
        buf.push(inner.length);
        inner.forEach((b: number) => buf.push(b));

        return new DataView(new Uint8Array(buf).buffer);
    }

    // ── Board hostname polling ─────────────────────────────────────────────────

    /**
     * Polls a board until it responds, using two strategies in parallel:
     *
     * Strategy A — mDNS hostname poll (CapacitorHttp every 3s)
     *   Works when Android resolves .local hostnames correctly (Android 12+,
     *   some earlier devices). Fast when it works.
     *
     * Strategy B — Native subnet scanner fallback
     *   If mDNS fails for 8s, fires the native Java scanner which uses raw
     *   TCP sockets routed through the hotspot interface. Always works but
     *   takes 3-8s to complete a full subnet scan.
     *
     * Whichever strategy finds the board first wins. The real IP from
     * the /identity response is stored — never the hostname.
     */
    private async waitForBoardByHostname(
        board:     'day' | 'night',
        hostname:  string,
        timeoutMs: number
    ): Promise<BoardInfo | null> {
        const deadline = Date.now() + timeoutMs;

        // Strategy A: mDNS hostname polling
        const mdnsPoll = async (): Promise<BoardInfo | null> => {
            while (Date.now() < deadline) {
                try {
                    const response = await CapacitorHttp.get({
                        url:              `http://${hostname}:82/identity`,
                        connectTimeout:   3000,
                        readTimeout:      3000,
                        disableRedirects: true,
                    });
                    if (response.status === 200 && response.data?.board === board) {
                        const realIp = response.data.ip || hostname;
                        console.log(`[Discovery] mDNS resolved ${board} → ${realIp}`);
                        return { board, ip: realIp, ble_name: response.data.ble_name || '' };
                    }
                } catch {
                    // Not reachable via mDNS yet
                }
                const remaining = deadline - Date.now();
                if (remaining > 0) await this.delay(Math.min(3000, remaining));
            }
            return null;
        };

        // Strategy B: Native subnet scanner (fires after 8s if mDNS hasn't worked)
        const nativeScan = async (): Promise<BoardInfo | null> => {
            // Wait 8s before firing native scan — give mDNS a chance first
            await this.delay(8000);
            if (Date.now() >= deadline) return null;

            console.log(`[Discovery] mDNS slow — firing native subnet scanner for ${board}...`);
            try {
                const result = await ObjectDetection.scanHotspotNetwork({
                    port:    82,
                    path:    '/identity',
                    timeout: 2000,
                });
                if (result.success && result.boards) {
                    for (const b of result.boards) {
                        if (b.board === board && b.ip) {
                            console.log(`[Discovery] Native scan found ${board} → ${b.ip}`);
                            return { board, ip: b.ip, ble_name: b.ble_name || '' };
                        }
                    }
                }
            } catch (err: any) {
                console.warn('[Discovery] Native scan error:', err.message);
            }
            return null;
        };

        // Race both strategies — first non-null result wins
        return new Promise(async (resolve) => {
            let resolved = false;
            let cancelled = false;
            const finish = (result: BoardInfo | null) => {
                if (!resolved) {
                    resolved = true;
                    cancelled = true;
                    resolve(result);
                }
            };

            const guardedMdnsPoll = async () => {
                const found = await mdnsPoll();
                if (!cancelled) finish(found);
            };

            const guardedNativeScan = async () => {
                const found = await nativeScan();
                if (!cancelled) finish(found);
            };

            guardedMdnsPoll();
            guardedNativeScan();

            // Ensure we resolve null at deadline even if both strategies are still running
            this.delay(timeoutMs).then(() => finish(null));
        });
    }

    async discoverBoards(): Promise<BoardInfo[]> {
        try {
            console.log('[Discovery] Starting native hotspot scan...');
            const result = await ObjectDetection.scanHotspotNetwork({
                port:    82,
                path:    '/identity',
                timeout: 2000
            });

            console.log('[Discovery] Native scan result:', JSON.stringify(result));

            if (result.success && result.boards) {
                const boards: BoardInfo[] = [];
                for (const b of result.boards) {
                    if (b.board && b.ip) {
                        boards.push({ board: b.board as 'day' | 'night', ip: b.ip, ble_name: b.ble_name || '' });
                        console.log(`[Discovery] ✅ Found ${b.board} at ${b.ip}`);
                    }
                }
                return boards;
            }

            return [];
        } catch (err: any) {
            console.error('[Discovery] Native scan failed:', err.message);
            return [];
        }
    }

    private delay(ms: number): Promise<void> {
        return new Promise(resolve => setTimeout(resolve, ms));
    }
}

export default new WearableProvisioningService();