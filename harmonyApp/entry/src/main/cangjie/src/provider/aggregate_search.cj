package com.yfuse.harmony.entry.provider

import std.collection.*
import com.yfuse.harmony.entry.app.*
import com.yfuse.harmony.entry.data.*

public class AggregateSearchResult {
    public let groups: ArrayList<SearchResultGroup>
    public let failures: HashMap<String, ApiFailure>
    public init(groups: ArrayList<SearchResultGroup>, failures: HashMap<String, ApiFailure>) {
        this.groups = groups; this.failures = failures
    }
}

/** Fan-out preserves per-server grouping and permits partial success. */
public class AggregateSearchRepository {
    private let media: MediaRepository
    private var generation: UInt64 = 0
    public init(media: MediaRepository) { this.media = media }

    public func search(
        servers: ArrayList<SavedServer>,
        query: String,
        limitPerServer: Int64,
        completion: (UInt64, AggregateSearchResult) -> Unit
    ): UInt64 {
        generation++
        let current = generation
        if (query.isEmpty() || servers.isEmpty()) {
            completion(current, AggregateSearchResult(ArrayList<SearchResultGroup>(), HashMap<String, ApiFailure>()))
            return current
        }
        let groups = ArrayList<SearchResultGroup>()
        let failures = HashMap<String, ApiFailure>()
        var remaining = servers.size
        for (server in servers) {
            media.search(server, query, 0, limitPerServer, { result =>
                if (current == generation) {
                    match (result) {
                        case Failure(problem) => failures[server.id] = problem
                        case Success(page) => if (!page.items.isEmpty()) { groups.append(SearchResultGroup(server.id, server.serverName, page.items)) }
                    }
                    remaining--
                    if (remaining == 0) { completion(current, AggregateSearchResult(groups, failures)) }
                }
            })
        }
        return current
    }

    public func cancel(): Unit { generation++ }
}
