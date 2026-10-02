package com.linroid.ketch.app.instance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal actual fun appForegroundChanges(): Flow<Boolean> = emptyFlow()
