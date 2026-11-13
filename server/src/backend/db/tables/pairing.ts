import { randomBytes } from "node:crypto";
import { db } from "../connection";
import { nowMs, sqlDateOffset, sqlNow } from "../dateUtils";

const PAIRING_TTL_MS = 10 * 60 * 1000;
const PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

function formatPairingCode(raw: string): string {
  return `${raw.slice(0, 4)}-${raw.slice(4)}`;
}

export function normalizePairingCode(input: string): string {
  return input.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

export async function createPairingCode(): Promise<{
  code: string;
  displayCode: string;
  expiresAt: number;
}> {
  const now = nowMs();
  await db
    .deleteFrom("pairing_codes")
    .where((expression) =>
      expression.or([
        expression("expires_at", "<=", sqlNow()),
        expression("used_at", "is not", null),
      ]),
    )
    .execute();

  const bytes = randomBytes(8);
  let raw = "";
  for (const byte of bytes)
    raw += PAIRING_ALPHABET[byte % PAIRING_ALPHABET.length];

  const expiresAt = now + PAIRING_TTL_MS;
  await db
    .insertInto("pairing_codes")
    .values({
      code: raw,
      created_at: sqlNow(),
      expires_at: sqlDateOffset(PAIRING_TTL_MS / 1000),
    })
    .execute();

  return { code: raw, displayCode: formatPairingCode(raw), expiresAt };
}

/** Atomically consume a one-time code. Returns false for used, expired, or unknown codes. */
export async function consumePairingCode(input: string): Promise<boolean> {
  const code = normalizePairingCode(input);
  if (code.length !== 8) return false;
  const result = await db
    .updateTable("pairing_codes")
    .set({ used_at: sqlNow() })
    .where("code", "=", code)
    .where("used_at", "is", null)
    .where("expires_at", ">", sqlNow())
    .executeTakeFirst();
  return Number(result?.numUpdatedRows ?? 0n) > 0;
}
