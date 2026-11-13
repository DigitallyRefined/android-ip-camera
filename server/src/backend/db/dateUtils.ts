import { sql, type RawBuilder } from "kysely";
import { databaseType } from "./connection";
import type { DateValue } from "./schema";

/** Current time truncated to whole seconds, in epoch milliseconds. */
export function nowMs(): number {
  return Math.floor(Date.now() / 1000) * 1000;
}

/** The current database time with whole-second precision. */
export function sqlNow(): RawBuilder<DateValue> {
  if (databaseType === "sqlite") {
    return sql<DateValue>`strftime('%Y-%m-%dT%H:%M:%SZ', 'now')`;
  }
  return sql<DateValue>`CURRENT_TIMESTAMP(0)`;
}

/** Database time offset by a whole number of seconds. */
export function sqlDateOffset(seconds: number): RawBuilder<DateValue> {
  if (databaseType === "postgres") {
    return sql<DateValue>`CURRENT_TIMESTAMP(0) + (${seconds} * interval '1 second')`;
  }
  if (databaseType === "mysql") {
    return sql<DateValue>`TIMESTAMPADD(SECOND, ${seconds}, CURRENT_TIMESTAMP(0))`;
  }
  return sql<DateValue>`strftime('%Y-%m-%dT%H:%M:%SZ', 'now', ${`${seconds} seconds`})`;
}

/** Normalize a date value returned by any supported dialect to milliseconds. */
export function dateMs(value: number | string | Date): number {
  if (value instanceof Date) return value.getTime();
  if (typeof value === "number") return value;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : new Date(value).getTime();
}

/** Date columns differ by dialect. */
export function dateType(): "timestamptz(0)" | "datetime" | "date" {
  if (databaseType === "postgres") return "timestamptz(0)";
  if (databaseType === "mysql") return "datetime";
  return "date";
}
