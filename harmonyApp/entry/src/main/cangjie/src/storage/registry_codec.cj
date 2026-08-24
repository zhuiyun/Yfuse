package com.yfuse.harmony.entry.storage

import encoding.json.*
import std.collection.*
import com.yfuse.harmony.entry.data.*

private func providerName(value: ProviderKind): String {
    return match (value) {
        case ProviderKind.Emby => "emby"
        case ProviderKind.Jellyfin => "jellyfin"
        case ProviderKind.Plex => "plex"
    }
}

private func providerFromName(value: String): ProviderKind {
    return match (value) {
        case "jellyfin" => ProviderKind.Jellyfin
        case "plex" => ProviderKind.Plex
        case _ => ProviderKind.Emby
    }
}

private func healthName(value: ServerHealth): String {
    return match (value) {
        case ServerHealth.Unknown => "unknown"
        case ServerHealth.Probing => "probing"
        case ServerHealth.Online => "online"
        case ServerHealth.Offline => "offline"
        case ServerHealth.Unauthorized => "unauthorized"
    }
}

private func healthFromName(value: String): ServerHealth {
    return match (value) {
        case "probing" => ServerHealth.Probing
        case "online" => ServerHealth.Online
        case "offline" => ServerHealth.Offline
        case "unauthorized" => ServerHealth.Unauthorized
        case _ => ServerHealth.Unknown
    }
}

private func optionalString(object: JsonObject, key: String, fallback!: String = ""): String {
    return match (object.get(key)) {
        case Some(value) => try { value.asString().getValue() } catch (_: Exception) { fallback }
        case None => fallback
    }
}

private func optionalInt(object: JsonObject, key: String, fallback!: Int64 = 0): Int64 {
    return match (object.get(key)) {
        case Some(value) => try { value.asInt().getValue() } catch (_: Exception) { fallback }
        case None => fallback
    }
}

private func optionalBool(object: JsonObject, key: String, fallback!: Bool = false): Bool {
    return match (object.get(key)) {
        case Some(value) => try { value.asBool().getValue() } catch (_: Exception) { fallback }
        case None => fallback
    }
}

public class JsonRegistryCodec <: RegistryCodec {
    public func decode(document: String): ApiResult<(ArrayList<SavedServer>, String)> {
        try {
            let root = JsonValue.fromStr(document).asObject()
            if (optionalInt(root, "schemaVersion") != 1) {
                return ApiResult<(ArrayList<SavedServer>, String)>.Failure(ApiFailure(FailureKind.Decode, "不支持的服务器配置版本"))
            }
            let result = ArrayList<SavedServer>()
            let values = match (root.get("servers")) {
                case Some(value) => value.asArray()
                case None => JsonArray()
            }
            for (entry in values.getItems()) {
                let source = entry.asObject()
                let routes = ArrayList<ServerRoute>()
                let routeValues = match (source.get("routes")) {
                    case Some(value) => value.asArray()
                    case None => JsonArray()
                }
                for (routeValue in routeValues.getItems()) {
                    let route = routeValue.asObject()
                    routes.append(ServerRoute(
                        optionalString(route, "id"),
                        optionalString(route, "name"),
                        optionalString(route, "url")
                    ))
                }
                let server = SavedServer(
                    optionalString(source, "id"),
                    providerFromName(optionalString(source, "provider")),
                    optionalString(source, "baseUrl"),
                    optionalString(source, "serverName"),
                    optionalString(source, "userId"),
                    optionalString(source, "userName"),
                    optionalString(source, "secretRef"),
                    routes: routes,
                    activeRouteId: optionalString(source, "activeRouteId", fallback: "primary"),
                    iconEmoji: optionalString(source, "iconEmoji"),
                    iconTintArgb: UInt32(optionalInt(source, "iconTintArgb")),
                    health: healthFromName(optionalString(source, "health")),
                    localCleartextConfirmed: optionalBool(source, "localCleartextConfirmed")
                )
                if (!server.id.isEmpty() && !server.secretRef.isEmpty()) { result.append(server) }
            }
            return ApiResult<(ArrayList<SavedServer>, String)>.Success((result, optionalString(root, "defaultServerId")))
        } catch (_: Exception) {
            return ApiResult<(ArrayList<SavedServer>, String)>.Failure(ApiFailure(FailureKind.Decode, "服务器配置损坏"))
        }
    }

    public func encode(servers: ArrayList<SavedServer>, defaultServerId: String): ApiResult<String> {
        let root = JsonObject()
        root.put("schemaVersion", JsonInt(1))
        root.put("defaultServerId", JsonString(defaultServerId))
        let values = JsonArray()
        for (server in servers) {
            let source = JsonObject()
            source.put("id", JsonString(server.id))
            source.put("provider", JsonString(providerName(server.provider)))
            source.put("baseUrl", JsonString(server.baseUrl))
            source.put("serverName", JsonString(server.serverName))
            source.put("userId", JsonString(server.userId))
            source.put("userName", JsonString(server.userName))
            source.put("secretRef", JsonString(server.secretRef))
            source.put("activeRouteId", JsonString(server.activeRouteId))
            source.put("iconEmoji", JsonString(server.iconEmoji))
            source.put("iconTintArgb", JsonInt(Int64(server.iconTintArgb)))
            source.put("health", JsonString(healthName(server.health)))
            source.put("localCleartextConfirmed", JsonBool(server.localCleartextConfirmed))
            let routes = JsonArray()
            for (route in server.routes) {
                let routeValue = JsonObject()
                routeValue.put("id", JsonString(route.id))
                routeValue.put("name", JsonString(route.name))
                routeValue.put("url", JsonString(route.url))
                routes.add(routeValue)
            }
            source.put("routes", routes)
            values.add(source)
        }
        root.put("servers", values)
        return ApiResult<String>.Success(root.toJsonString())
    }
}

/** Adapter keeps platform Preferences API details out of registry invariants. */
public interface StringPreferenceStore {
    func read(key: String): ApiResult<String>
    func writeAndFlush(key: String, value: String): ApiResult<Unit>
}

public class PreferenceRegistryDocumentStore <: RegistryDocumentStore {
    private let preferences: StringPreferenceStore
    public init(preferences: StringPreferenceStore) { this.preferences = preferences }
    public func load(): ApiResult<String> { preferences.read("server_registry_v1") }
    public func save(document: String): ApiResult<Unit> { preferences.writeAndFlush("server_registry_v1", document) }
}
