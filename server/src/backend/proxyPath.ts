/**
 * Extract the camera request target from Bun's /r/:deviceId/* route.
 * Bun exposes named route parameters but not the wildcard in request.params,
 * so use the URL pathname to preserve the forwarded path and its encoding.
 */
export function cameraProxyPath(url: URL, deviceId: string): string {
  const prefix = `/r/${encodeURIComponent(deviceId)}/`;
  if (!url.pathname.startsWith(prefix)) {
    throw new Error("Relay proxy URL did not match the device route");
  }
  return `/${url.pathname.slice(prefix.length)}${url.search}`;
}
