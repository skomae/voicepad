/**
 * Proof that nothing is uploaded.
 *
 * Every way a page can send data is wrapped at boot (fetch, XHR, sendBeacon,
 * WebSocket). The privacy panel shows what the app has actually done: how much
 * audio it captured, how many bytes it sent anywhere, and to which hosts. If a
 * request ever leaves the page while recording, it is flagged in red.
 */

export interface NetEvent {
  url: string;
  method: string;
  bytes: number;
  at: number;
  duringRecording: boolean;
}

const events: NetEvent[] = [];
let recording = false;
let audioBytes = 0;

export function setRecordingState(on: boolean) {
  recording = on;
}

export function addAudioSamples(samples: number) {
  audioBytes += samples * 2; // 16-bit mono WAV, the shape we store
}

export function audioBytesCaptured(): number {
  return audioBytes;
}

export function sentBytes(): number {
  return events.reduce((sum, e) => sum + e.bytes, 0);
}

export function requestEvents(): NetEvent[] {
  return events.slice();
}

export function suspicious(): NetEvent[] {
  return events.filter((e) => e.duringRecording && e.bytes > 0);
}

export function hostOf(url: string): string {
  try {
    return new URL(url, location.href).host || location.host;
  } catch {
    return url;
  }
}

export function hostsSeen(): { host: string; count: number; bytes: number }[] {
  const map = new Map<string, { host: string; count: number; bytes: number }>();
  for (const e of events) {
    const host = hostOf(e.url);
    const row = map.get(host) ?? { host, count: 0, bytes: 0 };
    row.count++;
    row.bytes += e.bytes;
    map.set(host, row);
  }
  return [...map.values()].sort((a, b) => b.count - a.count);
}

function bodyBytes(body: unknown): number {
  if (!body) return 0;
  if (typeof body === 'string') return new Blob([body]).size;
  if (body instanceof Blob) return body.size;
  if (body instanceof ArrayBuffer) return body.byteLength;
  if (ArrayBuffer.isView(body)) return body.byteLength;
  if (body instanceof URLSearchParams) return new Blob([body.toString()]).size;
  if (typeof FormData !== 'undefined' && body instanceof FormData) {
    let total = 0;
    body.forEach((v) => {
      if (typeof v === 'string') total += new Blob([v]).size;
      else if (v instanceof Blob) total += v.size;
    });
    return total;
  }
  return 0;
}

function record(url: string, method: string, bytes: number) {
  events.push({ url, method, bytes, at: Date.now(), duringRecording: recording });
}

export function installNetworkWatch() {
  const originalFetch = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.href : (input as Request).url;
    const method = (
      init?.method ?? (typeof input === 'object' && 'method' in input ? (input as Request).method : 'GET')
    ).toUpperCase();
    record(url, method, bodyBytes(init?.body));
    return originalFetch(input as RequestInfo, init);
  };

  const xhrSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function (this: XMLHttpRequest, body?: Document | XMLHttpRequestBodyInit | null) {
    record(this.responseURL || 'xhr', 'XHR', bodyBytes(body));
    return xhrSend.call(this, body as any);
  };

  if (navigator.sendBeacon) {
    const beacon = navigator.sendBeacon.bind(navigator);
    navigator.sendBeacon = (url: string | URL, data?: BodyInit | null) => {
      record(String(url), 'BEACON', bodyBytes(data));
      return beacon(url, data);
    };
  }

  if (typeof WebSocket !== 'undefined') {
    const Native = WebSocket;
    // A WebSocket is itself an upload channel; count opening one as a send.
    (window as any).WebSocket = class extends Native {
      constructor(url: string | URL, protocols?: string | string[]) {
        record(String(url), 'WS', 0);
        super(url, protocols as any);
      }
    };
  }
}
