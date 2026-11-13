import { Kysely } from "kysely";
import { BunMySqlDialect } from "kysely-bun-dialects/mysql";
import { BunPostgresDialect } from "kysely-bun-dialects/postgres";
import { BunSqliteDialect } from "kysely-bun-dialects/sqlite";
import type { Database } from "./schema";

const DEFAULT_DATABASE_URL = "sqlite:///data/relay.sqlite";

export type DatabaseType = "sqlite" | "mysql" | "postgres";

function databaseTypeFor(url: string): DatabaseType {
  const scheme = url.split(":", 1)[0]?.toLowerCase();
  if (scheme === "postgres" || scheme === "postgresql") return "postgres";
  if (scheme === "mysql" || scheme === "mariadb") return "mysql";
  if (scheme === "sqlite" || scheme === "file" || !url.includes("://"))
    return "sqlite";
  throw new Error(
    "Unsupported database URL. Use sqlite://, mysql://, or postgres://.",
  );
}

async function getDatabase(
  url: string,
  type: DatabaseType,
): Promise<Kysely<Database>> {
  const target = url.trim();

  if (type === "postgres") {
    const { SQL } = await import("bun");
    return new Kysely<Database>({
      dialect: new BunPostgresDialect({ sql: new SQL(target) }),
    });
  }

  if (type === "mysql") {
    const { SQL } = await import("bun");
    return new Kysely<Database>({
      dialect: new BunMySqlDialect({
        sql: new SQL(target, {
          foundRows: true,
          allowPublicKeyRetrieval: true,
        }),
      }),
    });
  }

  const { Database: SqliteDatabase } = await import("bun:sqlite");
  const filename = target.includes("://")
    ? target.slice(target.indexOf("://") + 3)
    : target;

  const database = new SqliteDatabase(filename);
  database.run("PRAGMA journal_mode = WAL");
  database.run("PRAGMA foreign_keys = ON");

  return new Kysely<Database>({
    dialect: new BunSqliteDialect({ database }),
  });
}

function configuredDatabaseUrl(): string {
  return process.env.DATABASE_URL || DEFAULT_DATABASE_URL;
}

const databaseUrl = configuredDatabaseUrl();

export const databaseType: DatabaseType = databaseTypeFor(databaseUrl);
export const db = await getDatabase(databaseUrl, databaseType);
