import { expect, test } from "bun:test";
import { RecordingCache } from "./recordingCache";

test("RecordingCache evicts the least recently used recording", () => {
  const cache = new RecordingCache(8);
  cache.set("first", Buffer.alloc(4, 1));
  cache.set("second", Buffer.alloc(4, 2));

  expect(cache.get("first")).toBeDefined();
  cache.set("third", Buffer.alloc(4, 3));

  expect(cache.get("first")).toBeDefined();
  expect(cache.get("second")).toBeUndefined();
  expect(cache.get("third")).toBeDefined();
});

test("RecordingCache invalidates a deleted folder and its children", () => {
  const cache = new RecordingCache(100);
  cache.set("device\n2026/11/09", Buffer.alloc(1));
  cache.set("device\n2026/11/09/clip.mp4", Buffer.alloc(1));
  cache.set("device\n2026/11/10/clip.mp4", Buffer.alloc(1));

  cache.invalidate("device\n2026/11/09");
  cache.invalidatePrefix("device\n2026/11/09/");

  expect(cache.get("device\n2026/11/09")).toBeUndefined();
  expect(cache.get("device\n2026/11/09/clip.mp4")).toBeUndefined();
  expect(cache.get("device\n2026/11/10/clip.mp4")).toBeDefined();
});

test("RecordingCache does not hold a recording larger than its budget", () => {
  const cache = new RecordingCache(4);
  cache.set("too-large", Buffer.alloc(5));

  expect(cache.get("too-large")).toBeUndefined();
});
