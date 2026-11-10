import type { JsonValue } from "./json";

/**
 * Camera names are user generated. Keep the allowed character set and length
 * in one place so the server validation and the browser prompt agree exactly.
 */
export const DEVICE_NAME_MAX_LENGTH = 100;

export const DEVICE_NAME_PATTERN = /^[A-Za-z0-9 -]{1,100}$/;

/**
 * Trim the input and return the canonical name, or null when it is invalid.
 * Leading and trailing spaces are not part of the stored name.
 */
export function normalizeDeviceName(input: JsonValue): string | null {
  if (typeof input !== "string") return null;
  const name = input.trim();
  if (!DEVICE_NAME_PATTERN.test(name)) return null;
  return name;
}
