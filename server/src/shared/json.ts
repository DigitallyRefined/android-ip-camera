/**
 * Minimal JSON shape used at request and WebSocket boundaries. `undefined` is
 * allowed because optional message fields are omitted by JSON.stringify.
 */
export type JsonPrimitive = string | number | boolean | null | undefined;

export type JsonValue = JsonPrimitive | JsonValue[] | JsonObject;

export type JsonObject = { [key: string]: JsonValue };
