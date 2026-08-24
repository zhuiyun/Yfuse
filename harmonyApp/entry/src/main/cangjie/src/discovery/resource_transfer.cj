package com.yfuse.harmony.entry.discovery

import std.collection.*
import com.yfuse.harmony.entry.data.*

public class ResourceChannel {
    public let id: String
    public let name: String
    public let endpoint: String
    public init(id: String, name: String, endpoint: String) { this.id = id; this.name = name; this.endpoint = endpoint }
}

public class CloudDirectory {
    public let id: String
    public let name: String
    public let parentId: String
    public init(id: String, name: String, parentId: String) { this.id = id; this.name = name; this.parentId = parentId }
}

public class DiscoveryResource {
    public let id: String
    public let title: String
    public let quality: String
    public let sizeBytes: Int64
    public let channelId: String
    public init(id: String, title: String, quality: String, sizeBytes: Int64, channelId: String) {
        this.id = id; this.title = title; this.quality = quality; this.sizeBytes = sizeBytes; this.channelId = channelId
    }
}

public interface ResourceDiscoveryPort {
    func search(query: String, channels: ArrayList<ResourceChannel>, completion: (ApiResult<ArrayList<DiscoveryResource>>) -> Unit): Unit
    func listDirectories(parentId: String, completion: (ApiResult<ArrayList<CloudDirectory>>) -> Unit): Unit
    func transfer(resource: DiscoveryResource, destinationId: String, completion: (ApiResult<String>) -> Unit): Unit
}

public class ResourceTransferCoordinator {
    private let port: ResourceDiscoveryPort
    public var destination: Option<CloudDirectory> = None
    public init(port: ResourceDiscoveryPort) { this.port = port }
    public func selectDestination(value: CloudDirectory): Unit { destination = Some(value) }
    public func transfer(resource: DiscoveryResource, completion: (ApiResult<String>) -> Unit): Unit {
        match (destination) {
            case None => completion(ApiResult<String>.Failure(ApiFailure(FailureKind.InvalidInput, "请先选择 123 转存目录")))
            case Some(value) => port.transfer(resource, value.id, completion)
        }
    }
}
