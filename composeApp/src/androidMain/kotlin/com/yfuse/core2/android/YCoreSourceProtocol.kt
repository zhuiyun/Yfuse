package com.yfuse.core2.android

import com.yfuse.core2.network.YSourceProtocol

// SMB paths are deliberately unencoded (jcifs reads them literally), so inspect the raw scheme.
internal fun String.sourceProtocolOrNull(): YSourceProtocol? =
    when (rawUriScheme()) {
        "http" -> YSourceProtocol.Http
        "https" -> YSourceProtocol.Https
        "smb" -> YSourceProtocol.Smb
        "ftp" -> YSourceProtocol.Ftp
        "yfuse-cloud" -> YSourceProtocol.CloudDrive
        "content" -> YSourceProtocol.Local
        else -> null
    }
