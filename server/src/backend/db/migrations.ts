import {
  Migrator,
  type Migration,
  type MigrationProvider,
} from "kysely/migration";
import type { Kysely } from "kysely";
import { db } from "./connection";
import { dateType } from "./dateUtils";
import type { Database } from "./schema";

/** The relay schema from 001, before user accounts and sharing were added. */
async function createInitialRelaySchema(db: Kysely<Database>): Promise<void> {
  const date = dateType();

  await db.schema
    .createTable("devices")
    .addColumn("id", "varchar(64)", (column) => column.primaryKey())
    .addColumn("name", "varchar(120)", (column) => column.notNull())
    .addColumn("token_hash", "varchar(64)", (column) => column.notNull())
    .addColumn("created_at", date, (column) => column.notNull())
    .addColumn("last_seen_at", date)
    .addColumn("notification_urls", "text")
    .addColumn("notification_last_sent", date)
    .execute();

  await db.schema
    .createTable("pairing_codes")
    .addColumn("code", "varchar(32)", (column) => column.primaryKey())
    .addColumn("created_at", date, (column) => column.notNull())
    .addColumn("expires_at", date, (column) => column.notNull())
    .addColumn("used_at", date)
    .execute();
}

async function dropInitialRelaySchema(db: Kysely<Database>): Promise<void> {
  await db.schema.dropTable("pairing_codes").ifExists().execute();
  await db.schema.dropTable("devices").ifExists().execute();
}

const migrations: Record<string, Migration> = {
  "001_initial_relay_schema": {
    async up(db: Kysely<Database>): Promise<void> {
      await createInitialRelaySchema(db);
    },

    async down(db: Kysely<Database>): Promise<void> {
      await dropInitialRelaySchema(db);
    },
  },
};

class StaticMigrationProvider implements MigrationProvider {
  async getMigrations(): Promise<Record<string, Migration>> {
    return migrations;
  }
}

export async function runMigrations(): Promise<void> {
  const migrator = new Migrator({
    db,
    provider: new StaticMigrationProvider(),
  });
  const { error, results } = await migrator.migrateToLatest();

  for (const result of results ?? []) {
    if (result.status === "Success") {
      console.log(`Database migration applied: ${result.migrationName}`);
    } else if (result.status === "Error") {
      console.error(`Database migration failed: ${result.migrationName}`);
    }
  }

  if (error) {
    throw error instanceof Error ? error : new Error(String(error));
  }
}
