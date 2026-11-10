import { runMigrations } from "./migrations";

export async function initializeDatabase(): Promise<void> {
  await runMigrations();
}
