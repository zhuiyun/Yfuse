package com.yfuse.harmony.entry.support

import com.yfuse.harmony.entry.data.*

public class ReleaseInfo {
    public let versionName: String
    public let versionCode: Int64
    public let notes: String
    public let mandatory: Bool
    public init(versionName: String, versionCode: Int64, notes: String, mandatory!: Bool = false) {
        this.versionName = versionName; this.versionCode = versionCode; this.notes = notes; this.mandatory = mandatory
    }
}

/** Installation stays with the trusted HarmonyOS distribution channel; the app never sideloads HAPs. */
public interface TrustedUpdatePort {
    func check(completion: (ApiResult<Option<ReleaseInfo>>) -> Unit): Unit
    func openStoreListing(): ApiResult<Unit>
}

public class UpdateManager {
    private let port: TrustedUpdatePort
    public init(port: TrustedUpdatePort) { this.port = port }
    public func check(completion: (ApiResult<Option<ReleaseInfo>>) -> Unit): Unit { port.check(completion) }
    public func install(): ApiResult<Unit> { port.openStoreListing() }
}
