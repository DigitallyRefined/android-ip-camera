import { expect, test } from "bun:test";
import {
  buildApprisePayload,
  formatNotificationUrls,
  parseNotificationUrls,
} from "./notifications";

test("one notification URL per non-empty line", () => {
  const result = parseNotificationUrls(
    "\n slack://TOKEN_A/TOKEN_B/TOKEN_C\r\ntgram://BOT_TOKEN/CHAT_ID\n\n",
  );

  expect(result).toEqual({
    ok: true,
    urls: ["slack://TOKEN_A/TOKEN_B/TOKEN_C", "tgram://BOT_TOKEN/CHAT_ID"],
  });
  if (result.ok) {
    expect(formatNotificationUrls(result.urls)).toBe(
      "slack://TOKEN_A/TOKEN_B/TOKEN_C\ntgram://BOT_TOKEN/CHAT_ID",
    );
  }
});

test("an empty setting disables notifications", () => {
  expect(parseNotificationUrls(" \n\t\n")).toEqual({ ok: true, urls: [] });
});

test("non-Apprise lines are rejected", () => {
  const result = parseNotificationUrls(
    "slack://TOKEN_A/TOKEN_B/TOKEN_C\nnot a notification url",
  );

  expect(result.ok).toBe(false);
  if (!result.ok) expect(result.error).toContain("Line 2");
});

test("user-entered quotes cannot escape the urls array", () => {
  const injected = 'slack://TOKEN"], "urls": ["extra", "title": "hijacked';
  const payload = buildApprisePayload([injected], "Motion detected", "body");
  const parsed = JSON.parse(payload) as {
    urls: string[];
    title: string;
    body: string;
  };

  expect(parsed.urls).toEqual([injected]);
  expect(parsed.title).toBe("Motion detected");
  expect(parsed.body).toBe("body");
});
