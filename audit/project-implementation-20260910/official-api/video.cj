/*
 * Copyright (c) 2025 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ohos.arkui.component.video

import ohos.arkui.component.common.{CommonMethodComponent, SeekMode, PlaybackSpeed, ImageFit}
import ohos.base.{ResourceStr, VoidCallback, Callback}
import ohos.ffi.{Callback1Param, RemoteDataLite, releaseFFIData}
import ohos.labels.{APILevel}
import ohos.arkui.component.util.{transResourceMediaToString}

foreign {
    func FfiOHOSAceFrameworkVideoCreate(src: CString, progressRate: Float64, preview: CString, controller: Int64): Unit

    func FfiOHOSAceFrameworkVideoMuted(muted: Bool): Unit

    func FfiOHOSAceFrameworkVideoAutoPlay(autoPlay: Bool): Unit

    func FfiOHOSAceFrameworkVideoControls(controls: Bool): Unit

    func FfiOHOSAceFrameworkVideoObjectFit(objectFit: Int32): Unit

    func FfiOHOSAceFrameworkVideoLoop(loop: Bool): Unit

    func FfiOHOSAceFrameworkVideoEnableAnalyzer(enable: Bool): Unit

    func FfiOHOSAceFrameworkVideoOnStart(callback: Int64): Unit

    func FfiOHOSAceFrameworkVideoOnPause(callback: Int64): Unit

    func FfiOHOSAceFrameworkVideoOnFinish(callback: Int64): Unit

    func FfiOHOSAceFrameworkVideoOnError(callback: Int64): Unit

    func FfiOHOSAceFrameworkVideoOnStop(callback: Int64): Unit

    func FfiOHOSAceFrameworkVideoOnPrepared(callback: Int64): Unit

    func FfiOHOSAceFrameworkVideoOnSeeking(callback: Int64): Unit

    func FfiOHOSAceFrameworkVideoOnSeeked(callback: Int64): Unit

    func FfiOHOSAceFrameworkVideoOnUpdate(callback: Int64): Unit

    func FfiOHOSAceFrameworkVideoOnFullscreenChange(callback: Int64): Unit

    func FfiOHOSAceFrameworkVideoControllerCreate(): Int64

    func FfiOHOSAceFrameworkVideoControllerStart(selfID: Int64): Unit

    func FfiOHOSAceFrameworkVideoControllerStop(selfID: Int64): Unit

    func FfiOHOSAceFrameworkVideoControllerPause(selfID: Int64): Unit

    func FfiOHOSAceFrameworkVideoControllerReset(selfID: Int64): Unit

    func FfiOHOSAceFrameworkVideoControllerSetCurrentTime(time: Int32, seekMode: Int32, selfID: Int64): Unit

    func FfiOHOSAceFrameworkVideoControllerRequestFullscreen(fullScreen: Bool, selfID: Int64): Unit

    func FfiOHOSAceFrameworkVideoControllerExitFullscreen(selfID: Int64): Unit
}

/**
 * Prepared information of the video.
 */
@!APILevel[
    since: "22",
    syscap: "SystemCapability.ArkUI.ArkUI.Full"
]
public class PreparedInfo {
    /**
     * The duration of the current video, in seconds.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public var duration: ?Int32

    init(duration: Int32) {
        this.duration = duration
    }
}

/**
 * Playback information of the video.
 */
@!APILevel[
    since: "22",
    syscap: "SystemCapability.ArkUI.ArkUI.Full"
]
public class PlaybackInfo {
    /**
     * The current video playback progress, expressed in seconds.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public var time: ?Int32

    init(time: Int32) {
        this.time = time
    }
}

/**
 * Fullscreen information of the video.
 */
@!APILevel[
    since: "22",
    syscap: "SystemCapability.ArkUI.ArkUI.Full"
]
public class FullscreenInfo {
    /**
     * The flag whether play in full screen.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public var fullscreen: ?Bool

    init(fullscreen: ?Bool) {
        this.fullscreen = fullscreen ?? false
    }
}

/**
 * Defines the video controller.
 */
@!APILevel[
    since: "22",
    syscap: "SystemCapability.ArkUI.ArkUI.Full"
]
public class VideoController <: RemoteDataLite {
    /**
     * Creates a new instance of VideoController.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public init() {
        super(unsafe { FfiOHOSAceFrameworkVideoControllerCreate() })
    }

    ~init() {
        releaseFFIData(myDataId)
    }

    /**
     * Provides events to play.
     * Starts or resumes video playback.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func start(): Unit {
        unsafe { FfiOHOSAceFrameworkVideoControllerStart(this.getID()) }
    }

    /**
     * Provides a pause event for playback.
     * Pauses the current video playback.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func pause(): Unit {
        unsafe { FfiOHOSAceFrameworkVideoControllerPause(this.getID()) }
    }

    /**
     * Provides an event to stop playback.
     * Stops the video playback and resets the playback position to the beginning.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func stop(): Unit {
        unsafe { FfiOHOSAceFrameworkVideoControllerStop(this.getID()) }
    }

    /**
     * Provide the progress method of video playback with SeekMode.
     *
     * @param { Int32 } value - The position to seek to, in seconds.
     * @param { ?SeekMode } seekMode - The seek mode.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func setCurrentTime(value: Int32, seekMode: ?SeekMode): Unit {
        let tmpSeekMode = seekMode ?? SeekMode.PreviousKeyframe
        unsafe {
            FfiOHOSAceFrameworkVideoControllerSetCurrentTime(value, tmpSeekMode.getValue(), this.getID())
        }
    }

    /**
     * Provides a full screen playback method.
     *
     * @param { ?Bool } value - Whether to play in full screen. The default value is true.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func requestFullscreen(value: ?Bool): Unit {
        unsafe {
            FfiOHOSAceFrameworkVideoControllerRequestFullscreen(value ?? false, this.getID())
        }
    }

    /**
     * Provides a method to exit full screen playback.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func exitFullscreen(): Unit {
        unsafe {
            FfiOHOSAceFrameworkVideoControllerExitFullscreen(this.getID())
        }
    }
}

/**
 * Defines Video Component.
 */
@!APILevel[
    since: "22",
    syscap: "SystemCapability.ArkUI.ArkUI.Full"
]
public class Video <: CommonMethodComponent<Video> & VideoAttribute {
    /**
     * Initializes the video component with the specified options.
     *
     * @param { ?ResourceStr } [src] - The source URL or resource of the video to play.
     * @param { ?PlaybackSpeed } [currentProgressRate] - The playback speed. The default value is PlaybackSpeed.Speed_Forward_1_00_X.
     * @param { ?ResourceStr } [previewUri] - The URI of the preview image to display before the video loads.
     * @param { ?VideoController } [controller] - The video controller for managing playback.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public init(
        src!: ?ResourceStr = None,
        currentProgressRate!: ?PlaybackSpeed = Option.None,
        previewUri!: ?ResourceStr = None,
        controller!: ?VideoController = None
    ) {
        let tmpCurrentProgressRate = currentProgressRate ?? SpeedForward100X
        let tmpController = controller ?? VideoController()
        let src_ = transResourceMediaToString(src ?? "")
        let previewUri_ = transResourceMediaToString(previewUri ?? "")
        unsafe {
            try (srcStr = LibC.mallocCString(src_).asResource(),
                 previewUriStr = LibC.mallocCString(previewUri_).asResource()
            ) {
                FfiOHOSAceFrameworkVideoCreate(srcStr.value, tmpCurrentProgressRate.getValue(), previewUriStr.value, tmpController.getID())
            }
        }
    }

    /**
     * Called when judging whether the video is muted.
     *
     * @param { ?Bool } value - Whether the video is muted. The default value is false.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func muted(value: ?Bool): This {
        unsafe {
            FfiOHOSAceFrameworkVideoMuted(value ?? false)
        }
        this
    }

    /**
     * Called when judging whether the video is played automatically.
     *
     * @param { ?Bool } value - Whether the video is played automatically. The default value is false.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func autoPlay(value: ?Bool): This {
        unsafe {
            FfiOHOSAceFrameworkVideoAutoPlay(value ?? false)
        }
        this
    }

    /**
     * Called when judging whether the control bar is displayed.
     *
     * @param { ?Bool } value - Whether the control bar is displayed. The default value is true.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func controls(value: ?Bool): This {
        unsafe {
            FfiOHOSAceFrameworkVideoControls(value ?? true)
        }
        this
    }

    /**
     * Called when determining the zoom type of the video source
     *
     * @param { ?ImageFit } value - The zoom type. The default value is ImageFit.Contain.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func objectFit(value: ?ImageFit): This {
        let tmpValue = value ?? ImageFit.Cover
        unsafe {
            FfiOHOSAceFrameworkVideoObjectFit(tmpValue.getValue())
        }
        this
    }

    /**
     * Called when judging whether the video is played circular.
     *
     * @param { ?Bool } value - Whether the video is played circular. The default value is false.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func loop(value: ?Bool): This {
        unsafe {
            FfiOHOSAceFrameworkVideoLoop(value ?? false)
        }
        this
    }

    /**
     * Called when the video is played.
     *
     * @param { ?VoidCallback } event - The callback function when the video is played.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func onStart(event: ?VoidCallback): This {
        let tmpEvent = event ?? { => }
        let wrapper = {
            _: CString => tmpEvent()
        }
        let lambdaData = Callback1Param<CString, Unit>(wrapper)
        unsafe {
            FfiOHOSAceFrameworkVideoOnStart(lambdaData.getID())
        }
        this
    }

    /**
     * Called when the video is paused.
     *
     * @param { ?VoidCallback } event - The callback function when the video is paused.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func onPause(event: ?VoidCallback): This {
        let tmpEvent = event ?? { => }
        let wrapper = {
            _: CString => tmpEvent()
        }
        let lambdaData = Callback1Param<CString, Unit>(wrapper)
        unsafe {
            FfiOHOSAceFrameworkVideoOnPause(lambdaData.getID())
        }
        this
    }

    /**
     * Called when the video playback ends.
     *
     * @param { ?VoidCallback } event - The callback function when the video playback ends.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func onFinish(event: ?VoidCallback): This {
        let tmpEvent = event ?? { => }
        let wrapper = {
            _: CString => tmpEvent()
        }
        let lambdaData = Callback1Param<CString, Unit>(wrapper)
        unsafe {
            FfiOHOSAceFrameworkVideoOnFinish(lambdaData.getID())
        }
        this
    }

    /**
     * Called when playback fails.
     *
     * @param { ?VoidCallback } event - The callback function when playback fails.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func onError(event: ?VoidCallback): This {
        let tmpEvent = event ?? { => }
        let wrapper = {
            _: CString => tmpEvent()
        }
        let lambdaData = Callback1Param<CString, Unit>(wrapper)
        unsafe {
            FfiOHOSAceFrameworkVideoOnError(lambdaData.getID())
        }
        this
    }

    /**
     * Called when the video preparation is complete.
     *
     * @param { ?Callback<PreparedInfo, Unit> } callback - The callback function when the video preparation is complete.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func onPrepared(callback: ?Callback<PreparedInfo, Unit>): This {
        let tmpCallback = callback ?? { _ => }
        let wrapper = {
            duration: Int32 => tmpCallback(PreparedInfo(duration))
        }
        let lambdaData = Callback1Param<Int32, Unit>(wrapper)
        unsafe {
            FfiOHOSAceFrameworkVideoOnPrepared(lambdaData.getID())
        }
        this
    }

    /**
     * Called when the time information is reported when the progress bar process is operated.
     *
     * @param { ?Callback<PlaybackInfo, Unit> } callback - The callback function when seeking.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func onSeeking(callback: ?Callback<PlaybackInfo, Unit>): This {
        let tmpCallback = callback ?? { _ => }
        let wrapper = {
            time: Int32 => tmpCallback(PlaybackInfo(time))
        }
        let lambdaData = Callback1Param<Int32, Unit>(wrapper)
        unsafe {
            FfiOHOSAceFrameworkVideoOnSeeking(lambdaData.getID())
        }
        this
    }

    /**
     * Called when the playback time information is reported after the operation progress bar is completed.
     *
     * @param { ?Callback<PlaybackInfo, Unit> } callback - The callback function after seeking.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func onSeeked(callback: ?Callback<PlaybackInfo, Unit>): This {
        let tmpCallback = callback ?? { _ => }
        let wrapper = {
            time: Int32 => tmpCallback(PlaybackInfo(time))
        }
        let lambdaData = Callback1Param<Int32, Unit>(wrapper)
        unsafe {
            FfiOHOSAceFrameworkVideoOnSeeked(lambdaData.getID())
        }
        this
    }

    /**
     * Called when the playback progress changes.
     *
     * @param { ?Callback<PlaybackInfo, Unit> } callback - The callback function when the playback progress changes.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func onUpdate(callback: ?Callback<PlaybackInfo, Unit>): This {
        let tmpCallback = callback ?? { _ => }
        let wrapper = {
            time: Int32 => tmpCallback(PlaybackInfo(time))
        }
        let lambdaData = Callback1Param<Int32, Unit>(wrapper)
        unsafe {
            FfiOHOSAceFrameworkVideoOnUpdate(lambdaData.getID())
        }
        this
    }

    /**
     * Called when the video enters and exits the full screen.
     *
     * @param { ?Callback<FullscreenInfo, Unit> } callback - The callback function when the video enters and exits the full screen.
     * @returns { This } Returns the Video component instance for chaining.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.ArkUI.ArkUI.Full"
    ]
    public func onFullscreenChange(callback: ?Callback<FullscreenInfo, Unit>): This {
        let tmpCallback = callback ?? { _ => }
        let wrapper = {
            fullscreen: Bool => tmpCallback(FullscreenInfo(fullscreen))
        }
        let lambdaData = Callback1Param<Bool, Unit>(wrapper)
        unsafe {
            FfiOHOSAceFrameworkVideoOnFullscreenChange(lambdaData.getID())
        }
        this
    }
}
