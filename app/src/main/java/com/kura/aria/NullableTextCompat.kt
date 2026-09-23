package com.kura.aria

/** Compatibility helper for Android MenuItem.title, whose platform type may be nullable. */
internal fun CharSequence?.startsWith(prefix: String): Boolean = this?.toString()?.startsWith(prefix) == true
