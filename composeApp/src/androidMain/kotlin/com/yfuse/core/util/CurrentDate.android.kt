package com.yfuse.core.util

import java.time.LocalDate

actual fun currentIsoDate(): String = LocalDate.now().toString()
