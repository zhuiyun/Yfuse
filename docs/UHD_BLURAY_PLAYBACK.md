# Blu-ray playback

YCore opens supported Blu-ray titles through its disc transport and libbluray. The playback settings also offer SenPlayer (SCore). Capabilities depend on the selected engine and source.

## YCore transport

Local seekable sources use the Android file or content provider. Remote images use AndroidTransportDiscBlockSource and 2,048-byte UDF blocks. For HTTP sources the transport checks the partial response and its byte range. Folder playback also requires access to the playlist and clip files.

## Validation

The standalone source build compiles the disc reader, demuxer and Vulkan companion. Run bash scripts/verify-ycore-native.sh against its output. For authored menus, subtitles, Dolby Vision, encoded audio output and physical-display behavior, consult the device evidence for the particular title, APK and hardware.
