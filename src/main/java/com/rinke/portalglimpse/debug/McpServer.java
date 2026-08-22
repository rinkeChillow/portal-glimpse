package com.rinke.portalglimpse.debug;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinke.portalglimpse.PortalGlimpse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * A minimal Model Context Protocol server, so an assistant can set the game up for a recording session
 * instead of the player pasting twenty commands by hand.
 *
 * <p>Two tools only — {@code execute_commands} and {@code get_player_info}. Deliberately not the
 * screenshot or area-scan tools the reference implementation also offers: this exists to dress a scene,
 * and every extra tool is extra surface for no benefit.
 *
 * <h2>Why this is safe to ship</h2>
 * It is an HTTP endpoint that runs game commands, which is not a thing to leave lying around in a public
 * mod. Three things keep it inert:
 * <ul>
 *   <li><b>Loopback only.</b> Bound to 127.0.0.1, so nothing off this machine can reach it — not the LAN,
 *       not the internet, regardless of firewall state.</li>
 *   <li><b>Off unless debug mode is on.</b> Started by {@code /pgdebug} and stopped when it is turned off,
 *       so for every normal player the socket is never opened at all.</li>
 *   <li><b>Singleplayer only.</b> Commands run through the integrated server, at permission level 2, as the
 *       player. On a remote server there is no integrated server and every call is refused — this can never
 *       be used to run commands somewhere the player isn't already the host.</li>
 * </ul>
 *
 * <p>Protocol is JSON-RPC 2.0 over POST, matching what MCP clients expect: {@code initialize},
 * {@code tools/list}, {@code tools/call}, and notifications (which get 202 and no body).
 */
public final class McpServer {

	/** Not 8080 — that is what every other local dev server grabs first, and a port clash here would look
	 * like the mod failing rather than the port being taken. */
	private static final int PORT = 25585;
	private static final int STOP_DELAY_SECONDS = 0;
	/** A command that never returns is a hung request; commands are near-instant, so this is generous. */
	private static final long COMMAND_TIMEOUT_SECONDS = 5;

	private static final Gson GSON = new Gson();
	private static HttpServer server;

	private McpServer() {
	}

	public static boolean isRunning() {
		return server != null;
	}

	public static int port() {
		return PORT;
	}

	/** Start listening. No-op if already running. Returns false if the port could not be bound. */
	public static synchronized boolean start() {
		if (server != null) {
			return true;
		}
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
			server.createContext("/mcp", McpServer::handle);
			// A single-threaded executor keeps requests serialised, which matters because each one hops onto
			// the client thread anyway — concurrency here would buy nothing and invite races.
			server.setExecutor(null);
			server.start();
			PortalGlimpse.LOGGER.info("Portal Glimpse: MCP server listening on http://127.0.0.1:{}/mcp", PORT);
			return true;
		} catch (IOException e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: could not start the MCP server on port {}", PORT, e);
			server = null;
			return false;
		}
	}

	public static synchronized void stop() {
		if (server != null) {
			server.stop(STOP_DELAY_SECONDS);
			server = null;
			PortalGlimpse.LOGGER.info("Portal Glimpse: MCP server stopped.");
		}
	}

	// -------------------------------------------------------------------------------------------------
	// Transport
	// -------------------------------------------------------------------------------------------------

	private static void handle(HttpExchange exchange) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			respond(exchange, 405, "{\"error\":\"POST only\"}");
			return;
		}
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		JsonObject request;
		try {
			request = JsonParser.parseString(body).getAsJsonObject();
		} catch (RuntimeException e) {
			respond(exchange, 400, error(null, -32700, "Parse error"));
			return;
		}

		JsonElement id = request.get("id");
		String method = request.has("method") ? request.get("method").getAsString() : "";

		// Notifications carry no id and expect no body — MCP clients send notifications/initialized.
		if (id == null) {
			respond(exchange, 202, "");
			return;
		}

		try {
			JsonObject result = switch (method) {
				case "initialize" -> initialize();
				case "tools/list" -> toolsList();
				case "tools/call" -> toolsCall(request.getAsJsonObject("params"));
				case "ping" -> new JsonObject();
				default -> null;
			};
			if (result == null) {
				respond(exchange, 200, error(id, -32601, "Unknown method: " + method));
				return;
			}
			JsonObject envelope = new JsonObject();
			envelope.addProperty("jsonrpc", "2.0");
			envelope.add("id", id);
			envelope.add("result", result);
			respond(exchange, 200, GSON.toJson(envelope));
		} catch (Exception e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: MCP request failed", e);
			respond(exchange, 200, error(id, -32603, String.valueOf(e.getMessage())));
		}
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
	}

	private static String error(JsonElement id, int code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		JsonObject envelope = new JsonObject();
		envelope.addProperty("jsonrpc", "2.0");
		envelope.add("id", id);
		envelope.add("error", err);
		return GSON.toJson(envelope);
	}

	// -------------------------------------------------------------------------------------------------
	// MCP methods
	// -------------------------------------------------------------------------------------------------

	private static JsonObject initialize() {
		JsonObject caps = new JsonObject();
		caps.add("tools", new JsonObject());
		JsonObject info = new JsonObject();
		info.addProperty("name", "portal-glimpse");
		info.addProperty("version", "1.0.0");
		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", "2024-11-05");
		result.add("capabilities", caps);
		result.add("serverInfo", info);
		return result;
	}

	private static JsonObject toolsList() {
		JsonArray tools = new JsonArray();

		JsonObject commandsProp = new JsonObject();
		commandsProp.addProperty("type", "array");
		commandsProp.addProperty("description", "Minecraft commands to run, with or without a leading slash");
		JsonObject items = new JsonObject();
		items.addProperty("type", "string");
		commandsProp.add("items", items);
		JsonObject execProps = new JsonObject();
		execProps.add("commands", commandsProp);
		JsonObject execSchema = new JsonObject();
		execSchema.addProperty("type", "object");
		execSchema.add("properties", execProps);
		JsonArray required = new JsonArray();
		required.add("commands");
		execSchema.add("required", required);
		tools.add(tool("execute_commands",
				"Run Minecraft commands in the player's singleplayer world, in order.", execSchema));

		JsonObject infoSchema = new JsonObject();
		infoSchema.addProperty("type", "object");
		infoSchema.add("properties", new JsonObject());
		tools.add(tool("get_player_info",
				"Read the player's position, facing, health, food, gamemode, dimension and full inventory.",
				infoSchema));

		JsonObject result = new JsonObject();
		result.add("tools", tools);
		return result;
	}

	private static JsonObject tool(String name, String description, JsonObject schema) {
		JsonObject t = new JsonObject();
		t.addProperty("name", name);
		t.addProperty("description", description);
		t.add("inputSchema", schema);
		return t;
	}

	private static JsonObject toolsCall(JsonObject params) throws Exception {
		String name = params.get("name").getAsString();
		JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments")
				: new JsonObject();

		JsonObject payload = switch (name) {
			case "execute_commands" -> executeCommands(args);
			case "get_player_info" -> playerInfo();
			default -> throw new IllegalArgumentException("Unknown tool: " + name);
		};

		// MCP returns tool output as content blocks; JSON goes in a text block as a JSON string.
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", GSON.toJson(payload));
		JsonArray content = new JsonArray();
		content.add(text);
		JsonObject result = new JsonObject();
		result.add("content", content);
		return result;
	}

	// -------------------------------------------------------------------------------------------------
	// Tools
	// -------------------------------------------------------------------------------------------------

	private static JsonObject executeCommands(JsonObject args) throws Exception {
		JsonArray list = args.getAsJsonArray("commands");
		List<String> commands = new ArrayList<>();
		for (JsonElement e : list) {
			String c = e.getAsString().trim();
			commands.add(c.startsWith("/") ? c.substring(1) : c);
		}

		MinecraftClient client = MinecraftClient.getInstance();
		JsonObject out = new JsonObject();
		JsonArray results = new JsonArray();
		int applied = 0;
		int failed = 0;

		for (String command : commands) {
			JsonObject entry = new JsonObject();
			entry.addProperty("command", command);
			try {
				// Onto the client thread, then the integrated server's. Commands mutate world state and must
				// not run on this HTTP thread.
				String status = runOnGameThread(client, command);
				entry.addProperty("status", status);
				if ("applied".equals(status)) {
					applied++;
				} else {
					failed++;
				}
			} catch (Exception e) {
				entry.addProperty("status", "execution_error");
				entry.addProperty("error", String.valueOf(e.getMessage()));
				failed++;
			}
			results.add(entry);
		}

		out.addProperty("totalCommands", commands.size());
		out.addProperty("appliedCount", applied);
		out.addProperty("failedCount", failed);
		out.add("results", results);
		return out;
	}

	private static String runOnGameThread(MinecraftClient client, String command) throws Exception {
		CompletableFuture<String> future = new CompletableFuture<>();
		client.execute(() -> {
			try {
				MinecraftServer server = client.getServer();
				if (server == null) {
					future.complete("refused_not_singleplayer");
					return;
				}
				ClientPlayerEntity local = client.player;
				if (local == null) {
					future.complete("refused_no_player");
					return;
				}
				ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(local.getUuid());
				if (serverPlayer == null) {
					future.complete("refused_no_player");
					return;
				}
				// Level 2 is the command-block tier: enough for give/tp/effect, short of stop or op.
				ServerCommandSource source = serverPlayer.getCommandSource().withLevel(2);
				server.getCommandManager().executeWithPrefix(source, command);
				future.complete("applied");
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});
		return future.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static JsonObject playerInfo() throws Exception {
		MinecraftClient client = MinecraftClient.getInstance();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		client.execute(() -> {
			try {
				ClientPlayerEntity player = client.player;
				if (player == null) {
					JsonObject none = new JsonObject();
					none.addProperty("error", "no player in world");
					future.complete(none);
					return;
				}
				JsonObject info = new JsonObject();
				JsonObject pos = new JsonObject();
				pos.addProperty("x", player.getX());
				pos.addProperty("y", player.getY());
				pos.addProperty("z", player.getZ());
				info.add("position", pos);
				JsonObject facing = new JsonObject();
				facing.addProperty("yaw", player.getYaw());
				facing.addProperty("pitch", player.getPitch());
				info.add("facing", facing);
				info.addProperty("health", player.getHealth());
				info.addProperty("maxHealth", player.getMaxHealth());
				info.addProperty("food", player.getHungerManager().getFoodLevel());
				info.addProperty("saturation", player.getHungerManager().getSaturationLevel());
				info.addProperty("gamemode", client.interactionManager != null
						? String.valueOf(client.interactionManager.getCurrentGameMode()) : "unknown");
				info.addProperty("dimension", player.getWorld().getRegistryKey().getValue().toString());
				info.addProperty("singleplayer", client.getServer() != null);

				// Inventory with slot numbers, so an edit can target exactly what is already there rather than
				// replacing the lot. 0-8 hotbar, 9-35 main, then the armour and offhand slots.
				JsonArray inv = new JsonArray();
				for (int i = 0; i < player.getInventory().size(); i++) {
					ItemStack stack = player.getInventory().getStack(i);
					if (stack.isEmpty()) {
						continue;
					}
					JsonObject item = new JsonObject();
					item.addProperty("slot", i);
					item.addProperty("id", stack.getItem().toString());
					item.addProperty("count", stack.getCount());
					item.addProperty("maxDamage", stack.getMaxDamage());
					item.addProperty("damage", stack.getDamage());
					item.addProperty("name", stack.getName().getString());
					inv.add(item);
				}
				info.add("inventory", inv);
				future.complete(info);
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});
		return future.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
