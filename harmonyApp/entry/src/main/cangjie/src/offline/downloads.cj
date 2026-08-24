package com.yfuse.harmony.entry.offline

import std.collection.*
import com.yfuse.harmony.entry.data.*

public enum OfflineState { | Queued | Resolving | Downloading | Paused | Verifying | Completed | Failed }

public class DownloadRecord {
    public let id: String
    public let serverId: String
    public let itemId: String
    public let sourceId: String
    public let title: String
    public let destination: String
    public var state: OfflineState = OfflineState.Queued
    public var downloadedBytes: Int64 = 0
    public var expectedBytes: Int64 = 0
    public var etag: String = ""
    public var checksum: String = ""
    public var error: String = ""
    public init(id: String, serverId: String, itemId: String, sourceId: String, title: String, destination: String) {
        this.id = id; this.serverId = serverId; this.itemId = itemId; this.sourceId = sourceId
        this.title = title; this.destination = destination
    }
}

public class ResolvedDownloadSource {
    public let url: String
    public let headersJson: String
    public let contentLength: Int64
    public let etag: String
    public let acceptsRanges: Bool
    public init(url: String, headersJson: String, contentLength: Int64, etag: String, acceptsRanges: Bool) {
        this.url = url; this.headersJson = headersJson; this.contentLength = contentLength
        this.etag = etag; this.acceptsRanges = acceptsRanges
    }
}

public interface DownloadSourceResolver {
    /** Resolves a fresh authenticated URL. Persistent records never store access tokens. */
    func resolve(record: DownloadRecord, completion: (ApiResult<ResolvedDownloadSource>) -> Unit): Unit
}

public interface DownloadSink {
    func size(record: DownloadRecord): Int64
    func append(record: DownloadRecord, offset: Int64, bytes: Array<UInt8>): ApiResult<Unit>
    func truncate(record: DownloadRecord): ApiResult<Unit>
    func verify(record: DownloadRecord, expectedChecksum: String): ApiResult<Unit>
    func remove(record: DownloadRecord): ApiResult<Unit>
}

public class DownloadCoordinator {
    private let resolver: DownloadSourceResolver
    private let sink: DownloadSink
    private let records = HashMap<String, DownloadRecord>()

    public init(resolver: DownloadSourceResolver, sink: DownloadSink) { this.resolver = resolver; this.sink = sink }

    public func enqueue(record: DownloadRecord): ApiResult<Unit> {
        if (record.id.isEmpty() || record.destination.isEmpty()) {
            return ApiResult<Unit>.Failure(ApiFailure(FailureKind.InvalidInput, "下载记录无效"))
        }
        records[record.id] = record
        record.state = OfflineState.Queued
        return ApiResult<Unit>.Success(())
    }

    public func resolve(recordId: String, completion: (ApiResult<ResolvedDownloadSource>) -> Unit): Unit {
        match (records.get(recordId)) {
            case None => completion(ApiResult<ResolvedDownloadSource>.Failure(ApiFailure(FailureKind.NotFound, "下载任务不存在")))
            case Some(record) =>
                record.state = OfflineState.Resolving
                resolver.resolve(record, { result =>
                    match (result) {
                        case Failure(problem) => record.state = OfflineState.Failed; record.error = problem.message
                        case Success(source) =>
                            let localSize = sink.size(record)
                            if (localSize > 0 && (!source.acceptsRanges || (!record.etag.isEmpty() && record.etag != source.etag))) {
                                match (sink.truncate(record)) { case Failure(problem) => record.state = OfflineState.Failed; record.error = problem.message; case Success(_) => record.downloadedBytes = 0 }
                            } else { record.downloadedBytes = localSize }
                            record.expectedBytes = source.contentLength
                            record.etag = source.etag
                            record.state = OfflineState.Downloading
                    }
                    completion(result)
                })
        }
    }

    public func acceptChunk(recordId: String, offset: Int64, bytes: Array<UInt8>): ApiResult<Unit> {
        return match (records.get(recordId)) {
            case None => ApiResult<Unit>.Failure(ApiFailure(FailureKind.NotFound, "下载任务不存在"))
            case Some(record) =>
                if (record.state != OfflineState.Downloading || offset != record.downloadedBytes) {
                    ApiResult<Unit>.Failure(ApiFailure(FailureKind.InvalidInput, "下载分片偏移不连续"))
                } else {
                    match (sink.append(record, offset, bytes)) {
                        case Failure(problem) => record.state = OfflineState.Failed; record.error = problem.message; ApiResult<Unit>.Failure(problem)
                        case Success(_) => record.downloadedBytes += bytes.size; ApiResult<Unit>.Success(())
                    }
                }
        }
    }

    public func finish(recordId: String): ApiResult<Unit> {
        return match (records.get(recordId)) {
            case None => ApiResult<Unit>.Failure(ApiFailure(FailureKind.NotFound, "下载任务不存在"))
            case Some(record) =>
                if (record.expectedBytes > 0 && record.downloadedBytes != record.expectedBytes) {
                    record.state = OfflineState.Failed
                    record.error = "下载文件长度不匹配"
                    ApiResult<Unit>.Failure(ApiFailure(FailureKind.Network, record.error))
                } else {
                    record.state = OfflineState.Verifying
                    match (sink.verify(record, record.checksum)) {
                        case Failure(problem) => record.state = OfflineState.Failed; record.error = problem.message; ApiResult<Unit>.Failure(problem)
                        case Success(_) => record.state = OfflineState.Completed; ApiResult<Unit>.Success(())
                    }
                }
        }
    }

    public func pause(recordId: String): Unit { if (let Some(record) <- records.get(recordId)) { record.state = OfflineState.Paused } }
}
