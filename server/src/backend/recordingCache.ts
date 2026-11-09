/**
 * A small LRU cache for downloaded recordings. Seeking renegotiates WebRTC and
 * needs the same MP4 again; without this, every scrub would refetch the whole
 * file from the camera.
 *
 * Buffers that are still being played remain alive through the mediabunny
 * Input even after eviction, so the cache limit bounds idle data only.
 */
export class RecordingCache {
  private readonly entries = new Map<string, Buffer>();
  private totalBytes = 0;

  constructor(private readonly maxBytes: number) {}

  get(key: string): Buffer | undefined {
    const data = this.entries.get(key);
    if (!data) return undefined;
    // Refresh insertion order for LRU eviction.
    this.entries.delete(key);
    this.entries.set(key, data);
    return data;
  }

  set(key: string, data: Buffer): void {
    if (data.byteLength > this.maxBytes) return;

    const existing = this.entries.get(key);
    if (existing) {
      this.totalBytes -= existing.byteLength;
      this.entries.delete(key);
    }

    this.entries.set(key, data);
    this.totalBytes += data.byteLength;

    while (this.totalBytes > this.maxBytes) {
      const oldestKey = this.entries.keys().next().value;
      if (oldestKey === undefined) break;
      const oldest = this.entries.get(oldestKey);
      this.entries.delete(oldestKey);
      if (oldest) this.totalBytes -= oldest.byteLength;
    }
  }

  invalidate(key: string): void {
    const data = this.entries.get(key);
    if (!data) return;
    this.entries.delete(key);
    this.totalBytes -= data.byteLength;
  }

  invalidatePrefix(prefix: string): void {
    for (const [key, data] of this.entries) {
      if (!key.startsWith(prefix)) continue;
      this.entries.delete(key);
      this.totalBytes -= data.byteLength;
    }
  }
}
