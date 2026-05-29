package com.tivimatelite.model

data class Channel(
    val name: String,
    val logoUrl: String?,
    val groupName: String?,
    val streamUrl: String,
    val epgText: String = "No EPG data"
)
