import { expect, test } from "bun:test";
import { formatDuration } from "./utils";

test("formatDuration renders minutes and seconds", () => {
  expect(formatDuration(0)).toBe("0:00");
  expect(formatDuration(65.9)).toBe("1:05");
});

test("formatDuration includes hours for long recordings", () => {
  expect(formatDuration(3_725)).toBe("1:02:05");
});

test("formatDuration clamps invalid values", () => {
  expect(formatDuration(Number.NaN)).toBe("0:00");
  expect(formatDuration(-4)).toBe("0:00");
});
