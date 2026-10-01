package com.linroid.ketch.app.platform

/**
 * How the app names the device it runs on, the embedded one: "This Mac", "This PC",
 * "This computer", "This phone", "This tablet", "This iPhone", "This iPad" or "This browser".
 * Its host name is secondary text.
 */
expect fun localDeviceNoun(): String
