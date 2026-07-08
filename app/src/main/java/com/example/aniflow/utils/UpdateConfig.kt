package com.example.aniflow.utils

import com.example.aniflow.security.Stormbreaker

object UpdateConfig {
    val UPDATE_JSON_URL: String get() = Stormbreaker.getUpdateUrl()
}
