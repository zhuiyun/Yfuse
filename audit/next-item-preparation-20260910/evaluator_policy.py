from edit import read, write, replace
A='composeApp/src/androidMain/kotlin/com/yfuse/core2/android/'
p=A+'AndroidCore2MediaProbe.kt'
replace(p,'''    private val platformProbe = AndroidCore2MediaProbe(context)

    fun takePreparedExtractor''','''    private val appContext = context.applicationContext
    private val platformProbe = AndroidCore2MediaProbe(context)

    /** Separate resource ownership, identical decoder/optimization/quirk policy. */
    fun newPreparationEvaluator(): AndroidCore2RouteEvaluator =
        AndroidCore2RouteEvaluator(
            context = appContext,
            decoderPreference = decoderPreference,
            optimizationPreference = optimizationPreference,
            capabilityProvider = capabilityProvider,
            strategy = strategy,
            quirkDatabase = quirkDatabase,
            deviceIdentity = deviceIdentity,
            nativeGpuRuntimeProbe = nativeGpuRuntimeProbe,
        )

    fun takePreparedExtractor''')
replace(A+'AndroidAdaptiveCore2YPlayer.kt','val preloadEvaluator = AndroidCore2RouteEvaluator(context)', 'val preloadEvaluator = routeEvaluator.newPreparationEvaluator()')
