import type { CameraAuthResult } from "./relay";

export function json<T>(data: T, status = 200): Response {
  return Response.json(data, {
    status,
    headers: { "Cache-Control": "no-store" },
  });
}

/** Parse the browser's Basic credentials; an absent header yields empty strings. */
export function parseBasicCredentials(request: Request): {
  username: string;
  password: string;
} {
  const header = request.headers.get("authorization")?.trim() ?? "";
  const match = /^Basic\s+(.+)$/i.exec(header);
  if (!match?.[1]) return { username: "", password: "" };
  try {
    const decoded = Buffer.from(match[1], "base64").toString("utf8");
    const separator = decoded.indexOf(":");
    if (separator < 0) return { username: "", password: "" };
    return {
      username: decoded.slice(0, separator),
      password: decoded.slice(separator + 1),
    };
  } catch {
    return { username: "", password: "" };
  }
}

export function authFailure(result: CameraAuthResult): Response {
  return Response.json(
    { error: result.error, reason: result.reason },
    { status: result.status, headers: { "Cache-Control": "no-store" } },
  );
}

export function publicBaseUrl(request: Request): string {
  const configured = process.env.PUBLIC_URL?.trim();
  if (configured) return configured.replace(/\/+$/, "");

  const url = new URL(request.url);
  const forwardedProto = request.headers
    .get("x-forwarded-proto")
    ?.split(",")[0]
    ?.trim();
  const forwardedHost = request.headers
    .get("x-forwarded-host")
    ?.split(",")[0]
    ?.trim();
  const proto = forwardedProto || url.protocol.replace(":", "");
  const host = forwardedHost || request.headers.get("host") || url.host;
  return `${proto}://${host}`;
}
