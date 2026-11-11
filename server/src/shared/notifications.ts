export const NOTIFICATION_URLS_MAX_LENGTH = 16_384;
export const NOTIFICATION_URLS_MAX_COUNT = 64;
export const NOTIFICATION_URL_MAX_LENGTH = 4_096;

export type ParseNotificationUrlsResult =
  { ok: true; urls: string[] } | { ok: false; error: string };

const APPRISE_URL_PATTERN = /^[a-z][a-z0-9+.-]*:\/\/\S+$/i;

/**
 * The dashboard stores one Apprise URL per line. Blank lines are ignored so a
 * trailing newline does not create an empty notification target.
 */
export function parseNotificationUrls(
  raw: string,
): ParseNotificationUrlsResult {
  if (raw.length > NOTIFICATION_URLS_MAX_LENGTH) {
    return {
      ok: false,
      error: `Notification settings must be at most ${NOTIFICATION_URLS_MAX_LENGTH} characters.`,
    };
  }

  const urls: string[] = [];
  const lines = raw.split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const url = (lines[index] ?? "").trim();
    if (!url) continue;
    if (url.length > NOTIFICATION_URL_MAX_LENGTH) {
      return {
        ok: false,
        error: `Line ${index + 1} must be at most ${NOTIFICATION_URL_MAX_LENGTH} characters.`,
      };
    }
    if (!APPRISE_URL_PATTERN.test(url)) {
      return {
        ok: false,
        error: `Line ${index + 1} must be an Apprise URL such as slack://TOKEN_A/TOKEN_B/TOKEN_C.`,
      };
    }
    urls.push(url);
    if (urls.length > NOTIFICATION_URLS_MAX_COUNT) {
      return {
        ok: false,
        error: `Use at most ${NOTIFICATION_URLS_MAX_COUNT} notification URLs.`,
      };
    }
  }

  return { ok: true, urls };
}

export function formatNotificationUrls(urls: readonly string[]): string {
  return urls.join("\n");
}

/**
 * Apprise accepts an array of URLs. Serializing the whole request keeps every
 * user-entered line inside its own string element; quotes or JSON fragments in
 * a URL cannot add elements or replace other fields.
 */
export function buildApprisePayload(
  urls: readonly string[],
  title: string,
  body: string,
): string {
  return JSON.stringify({
    urls: [...urls],
    title,
    body,
  });
}
